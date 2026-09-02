package dev.trim.pipeline.search

import dev.trim.model.Bracket
import dev.trim.model.Candidate
import dev.trim.model.EstimateConfidence
import dev.trim.model.EstimateRange
import dev.trim.model.FailureReason
import dev.trim.model.Metric
import dev.trim.model.QualityScore
import dev.trim.model.SkipReason
import dev.trim.pipeline.PipelineConfig
import dev.trim.pipeline.toFailureReason
import dev.trim.ports.Codec
import dev.trim.ports.FrameWindow
import dev.trim.ports.ScoreRequest
import dev.trim.ports.ScoreResult
import dev.trim.ports.Scorer
import dev.trim.ports.WindowEncodeResult

/**
 * The early abort (app-architecture §3): one encode at the **best-quality end** of the
 * bracket, before any search begins. If the safest setting in the bracket cannot reach the
 * target, no setting can — the bracket is monotone — so the file is rejected after a
 * single probe instead of a full binary search that was always going to fail.
 *
 * The probe is not wasted work when it succeeds either: its score is the search's known
 * lower bound, and its size is the first honest ([EstimateConfidence.PROBED]) estimate the
 * hub can show for this file.
 */
public class Prober(
    private val codec: Codec,
    private val scorer: Scorer,
    private val config: PipelineConfig = PipelineConfig(),
) {

    public suspend fun probe(
        candidate: Candidate,
        targetXpsnr: QualityScore,
    ): ProbeResult {
        require(targetXpsnr.metric == Metric.XPSNR) { "the search reasons in XPSNR" }
        val windows = config.searchWindows(candidate.video.durationMs)
        val safest = candidate.bracket.safest

        val encoded = when (val result = codec.encodeWindows(candidate.video.ref, safest, windows)) {
            is WindowEncodeResult.Failed ->
                return ProbeResult.Failed(result.error.toFailureReason(candidate.video.fingerprint))
            is WindowEncodeResult.Encoded -> result
        }

        val score = when (
            val scored = scorer.score(
                ScoreRequest(
                    source = candidate.video.ref,
                    encoded = encoded.handle,
                    windows = windows,
                    metric = Metric.XPSNR,
                    subsampleEveryNthFrame = config.searchSubsample,
                    normalisedWidth = config.searchNormalisedWidth,
                ),
            )
        ) {
            is ScoreResult.Failed ->
                return ProbeResult.Failed(FailureReason.EncoderError(scored.detail))
            is ScoreResult.Scored -> scored.score
        }

        if (score < targetXpsnr) {
            return ProbeResult.Skipped(SkipReason.CannotReachTarget(score, targetXpsnr))
        }

        return ProbeResult.Ready(
            bracket = candidate.bracket,
            windows = windows,
            safestScore = score,
            probedEstimate = extrapolate(candidate, encoded.bytes, windows),
        )
    }

    /**
     * Turns the bytes a few seconds of encode cost into a whole-file band. It is still a
     * band and not a number: the sampled windows are not the whole file, and pretending
     * otherwise is exactly the dishonesty the estimate types exist to prevent.
     */
    private fun extrapolate(
        candidate: Candidate,
        sampleBytes: Long,
        windows: List<FrameWindow>,
    ): EstimateRange {
        val sampledMs = windows.sumOf { it.durationMs }.coerceAtLeast(1L)
        val scaled = sampleBytes * candidate.video.durationMs / sampledMs
        return EstimateRange.around(scaled, PROBE_BAND, EstimateConfidence.PROBED)
    }

    private companion object {
        /** Narrower than the seed band, wider than a lie. */
        const val PROBE_BAND = 0.08
    }
}

public sealed interface ProbeResult {
    public data class Ready(
        val bracket: Bracket,
        val windows: List<FrameWindow>,
        val safestScore: QualityScore,
        val probedEstimate: EstimateRange,
    ) : ProbeResult

    public data class Skipped(val reason: SkipReason) : ProbeResult

    public data class Failed(val reason: FailureReason) : ProbeResult
}
