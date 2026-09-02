package dev.trim.model

/**
 * A video that triage believes is worth compressing, carrying the estimate the hub shows
 * before any work has been done.
 */
public data class Candidate(
    val video: Video,
    val estimate: EstimateRange,
    val bracket: Bracket,
    val predictedSetting: EncodeSetting?,
) {
    init {
        require(estimate.highBytes < video.sizeBytes) {
            "a candidate whose best case is not smaller than the source is not a candidate"
        }
        require(predictedSetting == null || bracket.contains(predictedSetting)) {
            "predicted setting $predictedSetting is outside the bracket $bracket"
        }
    }

    public val id: VideoId get() = video.id
}

/**
 * The outcome of triage. There are exactly two, and the rejected one cannot be built
 * without a reason — which is the whole point (CLAUDE.md: "no silent exits").
 */
public sealed interface TriageResult {
    public data class Accepted(val candidate: Candidate) : TriageResult
    public data class Rejected(val video: Video, val reason: SkipReason) : TriageResult
}
