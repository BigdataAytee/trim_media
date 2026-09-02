package dev.trim.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoTest {

    @Test
    fun `bits per pixel per second is bitrate over pixels per second`() {
        val v = sampleVideo(width = 1920, height = 1080, frameRate = 30.0, bitrateBps = 20_000_000)
        assertEquals(1920.0 * 1080.0 * 30.0, v.pixelsPerSecond)
        assertEquals(20_000_000.0 / (1920.0 * 1080.0 * 30.0), v.bitsPerPixelPerSecond, 1e-12)
    }

    @Test
    fun `the same bitrate is bloated at 1080p and thrifty at 4K60`() {
        val hd = sampleVideo(1920, 1080, 30.0, bitrateBps = 20_000_000)
        val uhd = sampleVideo(3840, 2160, 60.0, bitrateBps = 20_000_000)
        assertTrue(hd.bitsPerPixelPerSecond > uhd.bitsPerPixelPerSecond * 7)
    }

    @Test
    fun `HDR is transfer function or bit depth`() {
        assertTrue(sampleVideo(transfer = TransferFunction.PQ).isHdr)
        assertTrue(sampleVideo(transfer = TransferFunction.HLG).isHdr)
        assertTrue(sampleVideo(bitDepth = 10).isHdr)
        assertFalse(sampleVideo().isHdr)
    }

    @Test
    fun `secondary tracks include subtitle and metadata tracks`() {
        assertFalse(sampleVideo().hasSecondaryTrack)
        assertTrue(sampleVideo(otherTrackCount = 1).hasSecondaryTrack)
        assertTrue(sampleVideo(audioTrackCount = 2).hasSecondaryTrack)
        assertTrue(sampleVideo(videoTrackCount = 2).hasSecondaryTrack)
    }
}

internal fun sampleVideo(
    width: Int = 1920,
    height: Int = 1080,
    frameRate: Double = 30.0,
    bitrateBps: Long = 20_000_000,
    codec: VideoCodec = VideoCodec.H264,
    transfer: TransferFunction = TransferFunction.SDR,
    bitDepth: Int = 8,
    videoTrackCount: Int = 1,
    audioTrackCount: Int = 1,
    otherTrackCount: Int = 0,
    sizeBytes: Long = 400L * 1024 * 1024,
    id: String = "v1",
): Video = Video(
    id = VideoId(id),
    folderId = FolderId("dcim"),
    ref = StorageRef("content://$id"),
    displayName = "$id.mp4",
    sizeBytes = sizeBytes,
    durationMs = 60_000,
    width = width,
    height = height,
    frameRate = frameRate,
    codec = codec,
    bitrateBps = bitrateBps,
    transfer = transfer,
    colorRange = ColorRange.LIMITED,
    bitDepth = bitDepth,
    videoTrackCount = videoTrackCount,
    audioTrackCount = audioTrackCount,
    otherTrackCount = otherTrackCount,
    dateTakenEpochMs = 1_700_000_000_000,
    lastModifiedEpochMs = 1_700_000_000_000,
    fingerprint = SourceFingerprint(sizeBytes, 1_700_000_000_000, "hash-$id"),
)
