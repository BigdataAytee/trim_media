package dev.trim.pipeline.checks

import dev.trim.model.Candidate
import dev.trim.model.FailureReason
import dev.trim.model.SkipReason
import dev.trim.pipeline.PipelineConfig
import dev.trim.pipeline.toFailureReason
import dev.trim.ports.AnalysisResult
import dev.trim.ports.Codec

/** The outcome of a gate that can pass, skip with a reason, or fail. No fourth case. */
public sealed interface CheckResult {
    public data object Passed : CheckResult
    public data class Skipped(val reason: SkipReason) : CheckResult
    public data class Failed(val reason: FailureReason) : CheckResult
}

/**
 * Grain and sensor noise are incompressible: every bit spent on them is a bit that shows
 * when it is taken away. This runs a decode of a few windows and **before any encode**
 * (app-architecture §3), because an encode of a noisy file is work that was never going to
 * pay off.
 */
public class NoiseCheck(
    private val codec: Codec,
    private val config: PipelineConfig = PipelineConfig(),
) {
    public suspend fun check(candidate: Candidate): CheckResult {
        val windows = config.searchWindows(candidate.video.durationMs)
        return when (val analysis = codec.analyseWindows(candidate.video.ref, windows)) {
            is AnalysisResult.Failed -> CheckResult.Failed(analysis.error.toFailureReason(candidate.video.fingerprint))
            is AnalysisResult.Analysed ->
                if (analysis.highFrequencyEnergy >= config.noiseThreshold) {
                    CheckResult.Skipped(SkipReason.TooNoisy(analysis.highFrequencyEnergy))
                } else {
                    CheckResult.Passed
                }
        }
    }
}
