package dev.trim.ports

import dev.trim.model.ColorRange
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.TransferFunction
import dev.trim.model.VideoCodec

/**
 * Header-only facts about a media file. No decoding: the scan runs over a whole gallery,
 * so it must never open a decoder (app-architecture §3, Scanner: "header-only; no decode").
 */
public interface MediaInfo {
    public suspend fun probe(ref: StorageRef): MediaProbeResult
}

public sealed interface MediaProbeResult {
    public data class Readable(val header: MediaHeader) : MediaProbeResult

    /** The file exists but is not a video this app understands. */
    public data class Unreadable(val detail: String) : MediaProbeResult

    /** The file is gone or the grant was revoked. */
    public data object NotFound : MediaProbeResult
}

public data class MediaHeader(
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
    val fingerprint: SourceFingerprint,
)
