package dev.trim.pipeline.search

import dev.trim.model.Bracket
import dev.trim.model.EncodeSetting
import dev.trim.model.FailureReason
import dev.trim.model.Metric
import dev.trim.model.QualityScore
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.pipeline.PipelineConfig
import dev.trim.pipeline.toFailureReason
import dev.trim.ports.Codec
import dev.trim.ports.FrameWindow
import dev.trim.ports.ScoreRequest
import dev.trim.ports.ScoreResult
import dev.trim.ports.Scorer
import dev.trim.ports.WindowEncodeResult

/**
 * Finds the **most aggressive setting whose score still clears the target**.
 *
 * ### Precondition
 * Score is monotonically non-increasing in the quality index: a more aggressive setting
 * never scores higher than a less aggressive one. That is a property of every rate-control
 * mode, and it is what makes a binary search legal rather than a heuristic
 * (DECISIONS D4.3). The settings that clear the target therefore form a prefix of the
 * bracket, and the answer is the last element of that prefix.
 *
 * ### The probe budget
 * A cold search costs ⌈log2(bracket)⌉ probes. A predictor hit collapses it to **one
 * confirming probe** (app-architecture §3): if the predicted setting still clears the
 * target it is taken as the answer, and only if it does not does the search fall back —
 * and then only over the sub-bracket below the prediction, because everything at or above
 * it has just been shown to miss (DECISIONS D4.5).
 */
public class Searcher(
    private val codec: Codec,
    private val scorer: Scorer,
    private val config: PipelineConfig = PipelineConfig(),
) {

    public suspend fun search(
        source: StorageRef,
        fingerprint: SourceFingerprint,
        bracket: Bracket,
        windows: List<FrameWindow>,
        targetXpsnr: QualityScore,
        safestScore: QualityScore? = null,
        prediction: EncodeSetting? = null,
    ): SearchResult {
        require(targetXpsnr.metric == Metric.XPSNR) { "the search reasons in XPSNR" }
        val context = Context(source, fingerprint, windows, targetXpsnr)

        // The Prober has already established that the safest end clears the target. If it
        // has not been run, establish it here rather than assuming it.
        val lowScore = safestScore ?: when (val probed = context.scoreOf(bracket.safest)) {
            is Probe.Failed -> return SearchResult.Failed(probed.reason)
            is Probe.Scored -> probed.score
        }
        if (lowScore < targetXpsnr) {
            return SearchResult.NoSettingReachesTarget(bracket.safest, lowScore, context.probes)
        }

        if (prediction != null && bracket.contains(prediction)) {
            when (val confirmation = context.scoreOf(prediction)) {
                is Probe.Failed -> return SearchResult.Failed(confirmation.reason)
                is Probe.Scored ->
                    if (confirmation.score >= targetXpsnr) {
                        return SearchResult.Found(
                            setting = prediction,
                            score = confirmation.score,
                            probes = context.probes,
                            fromPrediction = true,
                        )
                    }
            }
            // The prediction overshot. Everything at or above it misses, so search below it.
            val narrowed = bracket.below(prediction)
                ?: return SearchResult.Found(bracket.safest, lowScore, context.probes, false)
            return binarySearch(context, narrowed, lowScore)
        }

        return binarySearch(context, bracket, lowScore)
    }

    /**
     * Invariant: [lowScore] is the score of `bracket.safest` and clears the target, so
     * `best` always names a setting known to be acceptable. The loop narrows
     * `(best, hi]` until nothing is left to test, and every setting above `best` has been
     * shown to miss.
     */
    private suspend fun binarySearch(
        context: Context,
        bracket: Bracket,
        lowScore: QualityScore,
    ): SearchResult {
        var best = bracket.safest
        var bestScore = lowScore
        var lo = bracket.safest.quality + 1
        var hi = bracket.mostAggressive.quality

        while (lo <= hi) {
            val mid = lo + (hi - lo + 1) / 2
            val setting = bracket.settingAt(mid)
            when (val probe = context.scoreOf(setting)) {
                is Probe.Failed -> return SearchResult.Failed(probe.reason)
                is Probe.Scored ->
                    if (probe.score >= context.target) {
                        best = setting
                        bestScore = probe.score
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
            }
        }
        return SearchResult.Found(best, bestScore, context.probes, fromPrediction = false)
    }

    private inner class Context(
        val source: StorageRef,
        val fingerprint: SourceFingerprint,
        val windows: List<FrameWindow>,
        val target: QualityScore,
    ) {
        var probes: Int = 0
            private set

        suspend fun scoreOf(setting: EncodeSetting): Probe {
            probes++
            val encoded = when (
                val result = codec.encodeWindows(source, setting, windows)
            ) {
                is WindowEncodeResult.Failed ->
                    return Probe.Failed(result.error.toFailureReason(fingerprint))
                is WindowEncodeResult.Encoded -> result.handle
            }
            return when (
                val scored = scorer.score(
                    ScoreRequest(
                        source = source,
                        encoded = encoded,
                        windows = windows,
                        metric = Metric.XPSNR,
                        subsampleEveryNthFrame = config.searchSubsample,
                        normalisedWidth = config.searchNormalisedWidth,
                    ),
                )
            ) {
                is ScoreResult.Failed -> Probe.Failed(FailureReason.EncoderError(scored.detail))
                is ScoreResult.Scored -> Probe.Scored(scored.score)
            }
        }
    }

    private sealed interface Probe {
        data class Scored(val score: QualityScore) : Probe
        data class Failed(val reason: FailureReason) : Probe
    }
}

public sealed interface SearchResult {
    public data class Found(
        val setting: EncodeSetting,
        val score: QualityScore,
        val probes: Int,
        val fromPrediction: Boolean,
    ) : SearchResult

    /**
     * Not even the safest setting cleared the target. In the assembled pipeline the Prober
     * has already rejected such a file, so this is the belt to the Prober's braces
     * (DECISIONS D4.4).
     */
    public data class NoSettingReachesTarget(
        val safest: EncodeSetting,
        val bestScore: QualityScore,
        val probes: Int,
    ) : SearchResult

    public data class Failed(val reason: FailureReason) : SearchResult
}
