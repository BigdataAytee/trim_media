package dev.trim.pipeline.encode

import dev.trim.model.FailureReason
import dev.trim.model.Metric
import dev.trim.model.QualityScore
import dev.trim.model.TempRef
import dev.trim.model.VerificationFailure
import dev.trim.model.Video
import dev.trim.pipeline.PipelineConfig
import dev.trim.ports.FileScoreRequest
import dev.trim.ports.ScoreResult
import dev.trim.ports.Scorer
import dev.trim.ports.Storage

/**
 * The last gate before anything the user can see changes.
 *
 * Verification is **tiered** (app-architecture §3): a single VMAF window is enough when the
 * search's XPSNR margin was wide, and three windows are scored when it was thin. The point
 * is not to save time on the easy cases — it is to spend time on the ones where a single
 * window could be unrepresentative.
 *
 * The cheap checks run first and in order of how bad it would be to miss them: the source
 * must not have changed underneath us, the output must actually be smaller, it must be the
 * same length, and the colour range must have survived.
 */
public class Verifier(
    private val storage: Storage,
    private val scorer: Scorer,
    private val config: PipelineConfig = PipelineConfig(),
) {

    public suspend fun verify(
        video: Video,
        temp: TempRef,
        encoded: EncodeOutcome.Encoded,
        targetVmaf: QualityScore,
        searchMargin: Double,
    ): VerifyResult {
        require(targetVmaf.metric == Metric.VMAF) { "verification reasons in VMAF" }

        val current = storage.fingerprint(video.ref)
        if (current != video.fingerprint) {
            return VerifyResult.Failed(
                FailureReason.SourceChanged(expected = video.fingerprint, actual = current),
            )
        }
        if (encoded.bytes >= video.sizeBytes) {
            return VerifyResult.Rejected(
                VerificationFailure.NotSmaller(video.sizeBytes, encoded.bytes),
            )
        }
        if (encoded.durationMs != video.durationMs) {
            return VerifyResult.Rejected(
                VerificationFailure.DurationChanged(video.durationMs, encoded.durationMs),
            )
        }
        if (encoded.colorRange != video.colorRange) {
            return VerifyResult.Rejected(
                VerificationFailure.ColorRangeChanged(video.colorRange, encoded.colorRange),
            )
        }

        val windows = windowsFor(video, searchMargin)
        val score = when (
            val scored = scorer.scoreFile(
                FileScoreRequest(
                    source = video.ref,
                    encoded = temp,
                    windows = windows,
                    metric = Metric.VMAF,
                    subsampleEveryNthFrame = config.verifySubsample,
                    normalisedWidth = config.verifyNormalisedWidth,
                ),
            )
        ) {
            is ScoreResult.Failed -> return VerifyResult.Failed(
                FailureReason.EncoderError(scored.detail),
            )
            is ScoreResult.Scored -> scored.score
        }

        return if (score < targetVmaf) {
            VerifyResult.Rejected(VerificationFailure.ScoreBelowTarget(score, targetVmaf))
        } else {
            VerifyResult.Verified(score, windows.size, encoded.bytes)
        }
    }

    /** One window when the search cleared its target comfortably, three when it did not. */
    private fun windowsFor(video: Video, searchMargin: Double) =
        config.searchWindows(video.durationMs).let { all ->
            if (searchMargin >= config.verifyBorderlineMargin) all.take(1) else all
        }
}

public sealed interface VerifyResult {
    public data class Verified(
        val score: QualityScore,
        val windowsScored: Int,
        val bytes: Long,
    ) : VerifyResult

    /** The encode was sound but not good enough. The original is untouched. */
    public data class Rejected(val failure: VerificationFailure) : VerifyResult

    /** Something went wrong that is not the encode's fault. The original is untouched. */
    public data class Failed(val reason: FailureReason) : VerifyResult
}
