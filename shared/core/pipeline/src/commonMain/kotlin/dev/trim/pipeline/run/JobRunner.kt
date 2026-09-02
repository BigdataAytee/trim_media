package dev.trim.pipeline.run

import dev.trim.model.Candidate
import dev.trim.model.CompressPhase
import dev.trim.model.CompressionResult
import dev.trim.model.FailureReason
import dev.trim.model.JobOutcome
import dev.trim.model.JobTrigger
import dev.trim.model.OriginalFate
import dev.trim.model.QualityScore
import dev.trim.model.QualityTarget
import dev.trim.model.SkipReason
import dev.trim.model.TempRef
import dev.trim.model.TriageResult
import dev.trim.model.Video
import dev.trim.pipeline.PipelineConfig
import dev.trim.pipeline.calibrate.CalibrationLookup
import dev.trim.pipeline.calibrate.CalibrationTable
import dev.trim.pipeline.checks.CheckResult
import dev.trim.pipeline.checks.HeadroomCheck
import dev.trim.pipeline.checks.NoiseCheck
import dev.trim.pipeline.encode.EncodeOutcome
import dev.trim.pipeline.encode.Encoder
import dev.trim.pipeline.encode.VerifyResult
import dev.trim.pipeline.encode.Verifier
import dev.trim.pipeline.predict.PredictionKey
import dev.trim.pipeline.predict.Predictor
import dev.trim.pipeline.replace.ReplaceRequest
import dev.trim.pipeline.replace.ReplaceResult
import dev.trim.pipeline.replace.Replacer
import dev.trim.pipeline.search.ProbeResult
import dev.trim.pipeline.search.Prober
import dev.trim.pipeline.search.SearchResult
import dev.trim.pipeline.search.Searcher
import dev.trim.pipeline.triage.Triage
import dev.trim.ports.Clock
import dev.trim.ports.OriginalDestination
import dev.trim.ports.Storage
import dev.trim.ports.Thermal

/**
 * Composes the stages and owns the state machine. No stage knows its neighbours; this is
 * the only class that knows the order.
 *
 * The two rules that shape it, both from app-architecture §10:
 *
 * - **No path may lose the file.** Every exit either has not touched user storage or has
 *   gone through the Replacer, whose rollback is tested separately.
 * - **No path may exit without a recorded outcome.** Every return is a [JobOutcome], all
 *   three of whose cases carry an explanation. There is no way to leave this function
 *   quietly, and the temp file is cleaned up in a `finally` rather than on the happy path.
 *
 * Retryable interruptions (a reclaimed codec, a thermal pause) are owned here rather than
 * by the stage that saw them, because only the runner knows whether waiting is allowed.
 */
public class JobRunner(
    private val storage: Storage,
    private val clock: Clock,
    private val thermal: Thermal,
    private val triage: Triage,
    private val noiseCheck: NoiseCheck,
    private val headroomCheck: HeadroomCheck,
    private val prober: Prober,
    private val searcher: Searcher,
    private val encoder: Encoder,
    private val verifier: Verifier,
    private val replacer: Replacer,
    private val predictor: Predictor,
    private val runPolicy: RunPolicy = RunPolicy(),
    private val calibration: CalibrationTable = CalibrationTable(),
    private val config: PipelineConfig = PipelineConfig(),
    private val deviceClass: String = "generic",
) {

    /** Diagnostics that must never be silent (§9, §10). Exported by hand, never uploaded. */
    public val diagnostics: MutableList<String> = mutableListOf()

    public suspend fun run(
        video: Video,
        request: JobRequest,
        onPhase: (CompressPhase) -> Unit = {},
    ): JobOutcome {
        val candidate = when (val triaged = triage.judge(video)) {
            is TriageResult.Rejected -> {
                onPhase(CompressPhase.Rejected(video.id, triaged.reason))
                return JobOutcome.Skipped(video.id, triaged.reason)
            }
            is TriageResult.Accepted -> triaged.candidate
        }

        val targetVmaf = request.qualityTarget.score
        val targetXpsnr = xpsnrTargetFor(targetVmaf)

        onPhase(CompressPhase.Checking(video.id))

        noiseCheck.check(candidate).let { result ->
            when (result) {
                is CheckResult.Skipped -> return skip(video, result.reason, onPhase)
                is CheckResult.Failed -> return fail(video, result.reason, onPhase)
                CheckResult.Passed -> Unit
            }
        }
        headroomCheck.check(candidate, targetVmaf).let { result ->
            when (result) {
                is CheckResult.Skipped -> return skip(video, result.reason, onPhase)
                is CheckResult.Failed -> return fail(video, result.reason, onPhase)
                CheckResult.Passed -> Unit
            }
        }

        onPhase(CompressPhase.FindingSetting(video.id, probesDone = 0))

        val probe = when (val probed = prober.probe(candidate, targetXpsnr)) {
            is ProbeResult.Skipped -> return skip(video, probed.reason, onPhase)
            is ProbeResult.Failed -> return fail(video, probed.reason, onPhase)
            is ProbeResult.Ready -> probed
        }
        onPhase(CompressPhase.FindingSetting(video.id, probesDone = 1))

        val search = searcher.search(
            source = video.ref,
            fingerprint = video.fingerprint,
            bracket = probe.bracket,
            windows = probe.windows,
            targetXpsnr = targetXpsnr,
            safestScore = probe.safestScore,
            prediction = candidate.predictedSetting,
        )
        val winner = when (search) {
            is SearchResult.Failed -> return fail(video, search.reason, onPhase)
            is SearchResult.NoSettingReachesTarget -> return skip(
                video,
                SkipReason.CannotReachTarget(search.bestScore, targetXpsnr),
                onPhase,
            )
            is SearchResult.Found -> search
        }
        onPhase(CompressPhase.FindingSetting(video.id, probesDone = 1 + winner.probes))

        val temp = storage.createTemp(video.displayName)
        try {
            val encoded = when (val outcome = encodeWithRetries(candidate, winner, temp, request, onPhase)) {
                is EncodeAttempt.Failed -> return fail(video, outcome.reason, onPhase)
                is EncodeAttempt.Succeeded -> outcome.encoded
            }

            onPhase(CompressPhase.Verifying(video.id))
            val verified = when (
                val result = verifier.verify(
                    video = video,
                    temp = temp,
                    encoded = encoded,
                    targetVmaf = targetVmaf,
                    searchMargin = winner.score.value - targetXpsnr.value,
                )
            ) {
                is VerifyResult.Failed -> return fail(video, result.reason, onPhase)
                is VerifyResult.Rejected -> return fail(
                    video,
                    FailureReason.VerificationFailed(result.failure),
                    onPhase,
                )
                is VerifyResult.Verified -> result
            }

            val committed = when (
                val result = replacer.commit(
                    ReplaceRequest(
                        video = video,
                        temp = temp,
                        fate = request.originalFate,
                        destination = destinationFor(request.originalFate),
                    ),
                )
            ) {
                is ReplaceResult.Refused -> return fail(video, result.reason, onPhase)
                is ReplaceResult.RolledBack -> return fail(video, result.toFailureReason(), onPhase)
                is ReplaceResult.Committed -> result
            }

            predictor.observe(
                key = PredictionKey.of(video, deviceClass),
                setting = winner.setting,
                sizeFraction = committed.compressedBytes.toDouble() / video.sizeBytes,
            )

            val result = CompressionResult(
                videoId = video.id,
                originalBytes = video.sizeBytes,
                compressedBytes = committed.compressedBytes,
                setting = winner.setting,
                verifiedScore = verified.score,
                originalFate = request.originalFate,
            )
            onPhase(CompressPhase.Done(video.id, result))
            return JobOutcome.Compressed(result)
        } finally {
            // Not "on the way out of the happy path" — always. A temp file left behind is
            // the user's disk space, and cleanup is a `finally`, not a hope (§5).
            storage.deleteTemp(temp)
        }
    }

    /**
     * The catch-wait-resume loop of §7. `KEY_PRIORITY = 1` means Android hands the encoder
     * to any foreground app that wants it, so a reclaim is routine rather than exceptional
     * — it must cost the file a delay, never the file itself.
     */
    private suspend fun encodeWithRetries(
        candidate: Candidate,
        winner: SearchResult.Found,
        temp: TempRef,
        request: JobRequest,
        onPhase: (CompressPhase) -> Unit,
    ): EncodeAttempt {
        val video = candidate.video
        var attempt = 0
        while (true) {
            when (val decision = runPolicy.decide(runStateFor(request, video))) {
                is RunDecision.Stop ->
                    return EncodeAttempt.Failed(FailureReason.Cancelled)
                is RunDecision.Pause -> {
                    onPhase(CompressPhase.Paused(video.id, decision.reason))
                    clock.sleep(decision.minimumMillis)
                    continue
                }
                RunDecision.Proceed -> Unit
            }

            val outcome = encoder.encode(
                source = video.ref,
                fingerprint = video.fingerprint,
                setting = winner.setting,
                destination = temp,
                onProgress = { fraction ->
                    onPhase(CompressPhase.Encoding(video.id, fraction, etaSeconds = null))
                },
            )
            when (outcome) {
                is EncodeOutcome.Encoded -> return EncodeAttempt.Succeeded(outcome)
                is EncodeOutcome.Failed -> return EncodeAttempt.Failed(outcome.reason)
                is EncodeOutcome.Interrupted -> {
                    attempt++
                    if (attempt > config.codecReclaimMaxRetries) {
                        return EncodeAttempt.Failed(
                            FailureReason.EncoderError(
                                "the video encoder was taken by another app " +
                                    "${config.codecReclaimMaxRetries} times in a row",
                            ),
                        )
                    }
                    onPhase(
                        CompressPhase.Paused(video.id, dev.trim.model.PauseReason.CodecReclaimed),
                    )
                    clock.sleep(config.codecReclaimWaitMs)
                }
            }
        }
    }

    private suspend fun runStateFor(request: JobRequest, video: Video): RunState = RunState(
        trigger = request.trigger,
        conditions = request.conditions,
        thermal = thermal.read(),
        requireFullCharge = request.requireFullCharge,
        stopBeforeAlarm = request.stopBeforeAlarm,
        workWhileUsingPhone = request.workWhileUsingPhone,
        nightlyByteCap = request.nightlyByteCap,
        bytesProcessedTonight = request.bytesProcessedTonight,
        estimatedJobMillis = video.durationMs,
    )

    /**
     * The XPSNR value that stands in for the user's VMAF target on this device. When no
     * calibration exists the generic curve is used and the fact is recorded — §9 requires
     * this to be flagged loudly rather than silently approximated.
     */
    private fun xpsnrTargetFor(targetVmaf: QualityScore): QualityScore {
        val lookup = calibration.xpsnrThresholdFor(targetVmaf, deviceClass)
        val base = when (lookup) {
            is CalibrationLookup.Calibrated -> lookup.threshold
            is CalibrationLookup.GenericCurve -> {
                if (lookup.diagnostic !in diagnostics) diagnostics += lookup.diagnostic
                lookup.threshold
            }
        }
        return base + config.searchTargetMargin
    }

    private fun destinationFor(fate: OriginalFate): OriginalDestination = when (fate) {
        is OriginalFate.KeptDays -> OriginalDestination.UndoBin
        is OriginalFate.Offloaded -> OriginalDestination.OffloadVolume(fate.volumeLabel)
        OriginalFate.Deleted -> OriginalDestination.Trash
    }

    private fun skip(
        video: Video,
        reason: SkipReason,
        onPhase: (CompressPhase) -> Unit,
    ): JobOutcome {
        onPhase(CompressPhase.Rejected(video.id, reason))
        return JobOutcome.Skipped(video.id, reason)
    }

    private fun fail(
        video: Video,
        reason: FailureReason,
        onPhase: (CompressPhase) -> Unit,
    ): JobOutcome {
        diagnostics += "${video.id}: ${reason.kind} — $reason"
        onPhase(CompressPhase.Rejected(video.id, reason))
        return JobOutcome.Failed(video.id, reason)
    }

    private sealed interface EncodeAttempt {
        data class Succeeded(val encoded: EncodeOutcome.Encoded) : EncodeAttempt
        data class Failed(val reason: FailureReason) : EncodeAttempt
    }
}

/** Everything about *this run* that is not about the file. */
public data class JobRequest(
    val trigger: JobTrigger,
    val qualityTarget: QualityTarget,
    val originalFate: OriginalFate,
    val conditions: dev.trim.ports.DeviceConditions,
    val requireFullCharge: Boolean = false,
    val stopBeforeAlarm: Boolean = true,
    val workWhileUsingPhone: Boolean = false,
    val nightlyByteCap: Long? = null,
    val bytesProcessedTonight: Long = 0,
)
