package dev.trim.pipeline.support

import dev.trim.model.ColorRange
import dev.trim.model.FolderId
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.TransferFunction
import dev.trim.model.Video
import dev.trim.model.VideoCodec
import dev.trim.model.VideoId

/**
 * A 1080p30 H.264 clip at 20 Mbps — roughly 0.32 bits per pixel per second, which is
 * comfortably above every efficiency threshold, so it is a candidate unless a test makes
 * it otherwise.
 */
internal fun video(
    id: String = "v1",
    width: Int = 1920,
    height: Int = 1080,
    frameRate: Double = 30.0,
    bitrateBps: Long = 20_000_000,
    codec: VideoCodec = VideoCodec.H264,
    transfer: TransferFunction = TransferFunction.SDR,
    bitDepth: Int = 8,
    videoTracks: Int = 1,
    audioTracks: Int = 1,
    otherTracks: Int = 0,
    sizeBytes: Long = 150L * 1024 * 1024,
    durationMs: Long = 60_000,
): Video = Video(
    id = VideoId(id),
    folderId = FolderId("dcim"),
    ref = StorageRef("content://$id"),
    displayName = "$id.mp4",
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    width = width,
    height = height,
    frameRate = frameRate,
    codec = codec,
    bitrateBps = bitrateBps,
    transfer = transfer,
    colorRange = ColorRange.LIMITED,
    bitDepth = bitDepth,
    videoTrackCount = videoTracks,
    audioTrackCount = audioTracks,
    otherTrackCount = otherTracks,
    dateTakenEpochMs = 1_700_000_000_000,
    lastModifiedEpochMs = 1_700_000_000_000,
    fingerprint = SourceFingerprint(sizeBytes, 1_700_000_000_000, "hash-$id"),
)
