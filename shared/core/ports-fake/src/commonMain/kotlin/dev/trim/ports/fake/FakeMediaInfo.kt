package dev.trim.ports.fake

import dev.trim.model.ColorRange
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.TransferFunction
import dev.trim.model.VideoCodec
import dev.trim.ports.MediaHeader
import dev.trim.ports.MediaInfo
import dev.trim.ports.MediaProbeResult

/**
 * Header facts from a table. Backed by [FakeStorage] so that a file the storage fake has
 * lost cannot still be probed — the two fakes tell one story.
 */
public class FakeMediaInfo(
    private val storage: FakeStorage,
    private val clock: FakeClock = FakeClock(),
) : MediaInfo {

    private val headers = mutableMapOf<String, MediaHeader>()
    private val unreadable = mutableSetOf<String>()

    /** Every probe served, in order — the Scanner must not probe a file twice. */
    public val probes: MutableList<String> = mutableListOf()

    public var delayMs: Long = 0

    public fun register(ref: StorageRef, header: MediaHeader) {
        headers[ref.value] = header
    }

    public fun markUnreadable(ref: StorageRef) {
        unreadable += ref.value
    }

    override suspend fun probe(ref: StorageRef): MediaProbeResult {
        if (delayMs > 0) clock.sleep(delayMs)
        probes += ref.value
        if (!storage.exists(ref)) return MediaProbeResult.NotFound
        if (ref.value in unreadable) return MediaProbeResult.Unreadable("not a video this app reads")
        val header = headers[ref.value] ?: return MediaProbeResult.Unreadable("no header registered")
        val entry = storage.files.getValue(ref.value)
        return MediaProbeResult.Readable(
            header.copy(
                fingerprint = SourceFingerprint(
                    sizeBytes = entry.bytes,
                    lastModifiedEpochMs = entry.lastModifiedEpochMs,
                    headTailHash = entry.hash,
                ),
                dateTakenEpochMs = entry.dateTakenEpochMs,
            ),
        )
    }

    public companion object {
        /** A plain 1080p30 H.264 header; override what a test cares about. */
        public fun header(
            durationMs: Long = 60_000,
            width: Int = 1920,
            height: Int = 1080,
            frameRate: Double = 30.0,
            codec: VideoCodec = VideoCodec.H264,
            bitrateBps: Long = 20_000_000,
            transfer: TransferFunction = TransferFunction.SDR,
            colorRange: ColorRange = ColorRange.LIMITED,
            bitDepth: Int = 8,
            videoTrackCount: Int = 1,
            audioTrackCount: Int = 1,
            otherTrackCount: Int = 0,
        ): MediaHeader = MediaHeader(
            durationMs = durationMs,
            width = width,
            height = height,
            frameRate = frameRate,
            codec = codec,
            bitrateBps = bitrateBps,
            transfer = transfer,
            colorRange = colorRange,
            bitDepth = bitDepth,
            videoTrackCount = videoTrackCount,
            audioTrackCount = audioTrackCount,
            otherTrackCount = otherTrackCount,
            dateTakenEpochMs = null,
            fingerprint = SourceFingerprint(1, 1, "placeholder"),
        )
    }
}
