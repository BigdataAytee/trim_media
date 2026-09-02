package dev.trim.pipeline.checks

import dev.trim.model.Candidate
import dev.trim.model.FailureReason
import dev.trim.model.Metric
import dev.trim.model.QualityScore
import dev.trim.model.SkipReason
import dev.trim.pipeline.PipelineConfig
import dev.trim.ports.ScoreResult
import dev.trim.ports.Scorer

/**
 * Some files cannot score above the target even against themselves — soft focus, heavy
 * compression already applied, a phone camera having a bad night. If the ceiling is below
 * target + margin, no setting could clear the target, so there is nothing to search for
 * (app-architecture §3).
 */
public class HeadroomCheck(
    private val scorer: Scorer,
    private val config: PipelineConfig = PipelineConfig(),
) {
    public suspend fun check(candidate: Candidate, targetVmaf: QualityScore): CheckResult {
        require(targetVmaf.metric == Metric.VMAF) { "the headroom check reasons in VMAF" }
        val required = targetVmaf + config.headroomMarginVmaf
        return when (val ceiling = scorer.ceiling(candidate.video.ref, Metric.VMAF)) {
            is ScoreResult.Failed ->
                CheckResult.Failed(FailureReason.EncoderError(ceiling.detail))
            is ScoreResult.Scored ->
                if (ceiling.score < required) {
                    CheckResult.Skipped(SkipReason.NoHeadroom(ceiling.score, required))
                } else {
                    CheckResult.Passed
                }
        }
    }
}
