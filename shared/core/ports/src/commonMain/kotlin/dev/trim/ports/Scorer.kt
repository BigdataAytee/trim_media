package dev.trim.ports

import dev.trim.model.Metric
import dev.trim.model.QualityScore
import dev.trim.model.StorageRef
import dev.trim.model.TempRef

/**
 * Perceptual scoring. Native XPSNR and libvmaf behind one C ABI that takes planar YUV
 * buffers, never file paths (app-architecture §9) — from Kotlin's side that is this port.
 */
public interface Scorer {

    /** Scores an encoded sample against its source windows — the search's unit of work. */
    public suspend fun score(request: ScoreRequest): ScoreResult

    /**
     * Scores a completed encode still sitting in scratch space against its source. This is
     * the Verifier's unit of work: the file exists but has not been committed anywhere.
     */
    public suspend fun scoreFile(request: FileScoreRequest): ScoreResult

    /**
     * The best score this source could achieve — its own ceiling. The HeadroomCheck skips
     * a file whose ceiling is below target + margin, because no setting could beat it.
     */
    public suspend fun ceiling(source: StorageRef, metric: Metric): ScoreResult
}

public data class ScoreRequest(
    val source: StorageRef,
    val encoded: EncodedSample,
    val windows: List<FrameWindow>,
    val metric: Metric,
    /** Every Nth frame: 5 while searching, 3 while verifying (§9). */
    val subsampleEveryNthFrame: Int,
    /** Both sides are scaled to this width before scoring (§9). */
    val normalisedWidth: Int,
) {
    init {
        require(windows.isNotEmpty()) { "scoring needs at least one window" }
        require(subsampleEveryNthFrame >= 1) { "subsample must be at least 1" }
        require(normalisedWidth > 0) { "normalisedWidth must be positive" }
    }
}

public data class FileScoreRequest(
    val source: StorageRef,
    val encoded: TempRef,
    val windows: List<FrameWindow>,
    val metric: Metric,
    val subsampleEveryNthFrame: Int,
    val normalisedWidth: Int,
) {
    init {
        require(windows.isNotEmpty()) { "scoring needs at least one window" }
        require(subsampleEveryNthFrame >= 1) { "subsample must be at least 1" }
        require(normalisedWidth > 0) { "normalisedWidth must be positive" }
    }
}

public sealed interface ScoreResult {
    public data class Scored(val score: QualityScore) : ScoreResult
    public data class Failed(val detail: String) : ScoreResult
}
