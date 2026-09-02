package dev.trim.model

/** The container-level codec of a video track. */
public enum class VideoCodec {
    H264,
    HEVC,
    AV1,
    VP9,
    UNKNOWN,
    ;

    public val displayName: String
        get() = when (this) {
            H264 -> "H.264"
            HEVC -> "HEVC"
            AV1 -> "AV1"
            VP9 -> "VP9"
            UNKNOWN -> "unknown codec"
        }
}

/** Transfer characteristics, the thing that makes a file HDR or not. */
public enum class TransferFunction {
    SDR,
    HLG,
    PQ,
    ;

    public val isHdr: Boolean get() = this != SDR
}

/**
 * Colour range of the encoded signal. Preserving it across an encode is a Milestone 3
 * requirement; the model carries it from Milestone 1 so nothing downstream has to invent
 * a default.
 */
public enum class ColorRange {
    LIMITED,
    FULL,
}

/**
 * A video as the Scanner found it: header-only facts, no decoded frames.
 *
 * Every field here comes from a container header via [dev.trim.ports.MediaInfo]. Nothing
 * in this type requires opening a decoder, because the scan must be cheap enough to run
 * over a whole gallery.
 */
public data class Video(
    val id: VideoId,
    val folderId: FolderId,
    val ref: StorageRef,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val codec: VideoCodec,
    val bitrateBps: Long,
    val transfer: TransferFunction,
    val colorRange: ColorRange,
    val bitDepth: Int,
    val videoTrackCount: Int,
    val audioTrackCount: Int,
    val otherTrackCount: Int,
    val dateTakenEpochMs: Long?,
    val lastModifiedEpochMs: Long,
    val fingerprint: SourceFingerprint,
) {
    init {
        require(sizeBytes > 0) { "sizeBytes must be positive" }
        require(durationMs > 0) { "durationMs must be positive" }
        require(width > 0 && height > 0) { "dimensions must be positive" }
        require(frameRate > 0.0) { "frameRate must be positive" }
        require(bitrateBps > 0) { "bitrateBps must be positive" }
        require(bitDepth >= 8) { "bitDepth must be at least 8" }
        require(videoTrackCount >= 1) { "a video must have at least one video track" }
        require(audioTrackCount >= 0 && otherTrackCount >= 0) { "track counts must not be negative" }
    }

    /** Pixels emitted per second of playback — the denominator of the triage rule. */
    public val pixelsPerSecond: Double get() = width.toDouble() * height.toDouble() * frameRate

    /**
     * Bits per pixel per second: the only bitrate measure triage is allowed to judge,
     * because a flat Mbps threshold punishes 4K60 and lets bloated 720p through
     * (app-architecture §3).
     */
    public val bitsPerPixelPerSecond: Double get() = bitrateBps.toDouble() / pixelsPerSecond

    public val isHdr: Boolean get() = transfer.isHdr || bitDepth > 8

    /** More than one video or audio track, or any subtitle/timed-metadata track. */
    public val hasSecondaryTrack: Boolean
        get() = videoTrackCount > 1 || audioTrackCount > 1 || otherTrackCount > 0
}

/**
 * A cheap identity snapshot of a source file, captured at scan time and re-checked
 * immediately before the Replacer touches anything. If it has moved, the pipeline stops
 * rather than replacing a file that is no longer the one it measured.
 */
public data class SourceFingerprint(
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val headTailHash: String,
) {
    init {
        require(headTailHash.isNotBlank()) { "headTailHash must not be blank" }
    }
}
