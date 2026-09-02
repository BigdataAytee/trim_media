package dev.trim.pipeline.scan

import dev.trim.model.FolderId
import dev.trim.model.StorageRef
import dev.trim.model.Video
import dev.trim.model.VideoId
import dev.trim.ports.MediaInfo
import dev.trim.ports.MediaProbeResult
import dev.trim.ports.Storage
import dev.trim.ports.StorageListResult

/**
 * The processed list, which app-architecture §12 calls a hard gate: a file Trim has
 * already compressed is never offered again, so generational loss is prevented
 * structurally rather than by warning copy. Backed by the database in core/data.
 */
public interface ProcessedLedger {
    public suspend fun isProcessed(ref: StorageRef): Boolean
}

/** A ledger that has never processed anything — the state of a fresh install. */
public object EmptyProcessedLedger : ProcessedLedger {
    override suspend fun isProcessed(ref: StorageRef): Boolean = false
}

/**
 * Walks the granted folders and turns files into [Video] rows using header facts only.
 * No decode happens here: the scan runs over an entire gallery, and a scan that opened a
 * decoder per file would be a scan nobody waits for (app-architecture §3).
 */
public class Scanner(
    private val storage: Storage,
    private val mediaInfo: MediaInfo,
    private val processed: ProcessedLedger = EmptyProcessedLedger,
) {

    public suspend fun scan(folders: List<FolderId>? = null): ScanReport {
        val targets = folders ?: storage.grantedFolders()
        val videos = mutableListOf<Video>()
        val alreadyProcessed = mutableListOf<StorageRef>()
        val unreadable = mutableListOf<UnreadableFile>()
        val denied = mutableListOf<FolderId>()
        val failed = mutableListOf<FolderFailure>()

        for (folder in targets) {
            when (val listing = storage.listVideos(folder)) {
                is StorageListResult.PermissionDenied -> denied += folder
                is StorageListResult.Failed -> failed += FolderFailure(folder, listing.detail)
                is StorageListResult.Listed -> {
                    for (ref in listing.refs) {
                        if (processed.isProcessed(ref)) {
                            alreadyProcessed += ref
                            continue
                        }
                        when (val probe = mediaInfo.probe(ref)) {
                            is MediaProbeResult.Readable ->
                                videos += toVideo(folder, ref, probe)
                            is MediaProbeResult.Unreadable ->
                                unreadable += UnreadableFile(ref, probe.detail)
                            MediaProbeResult.NotFound ->
                                unreadable += UnreadableFile(ref, "file disappeared during the scan")
                        }
                    }
                }
            }
        }
        return ScanReport(videos, alreadyProcessed, unreadable, denied, failed)
    }

    private fun toVideo(
        folder: FolderId,
        ref: StorageRef,
        probe: MediaProbeResult.Readable,
    ): Video {
        val header = probe.header
        return Video(
            id = VideoId(ref.value),
            folderId = folder,
            ref = ref,
            displayName = ref.value.substringAfterLast('/'),
            sizeBytes = header.fingerprint.sizeBytes,
            durationMs = header.durationMs,
            width = header.width,
            height = header.height,
            frameRate = header.frameRate,
            codec = header.codec,
            bitrateBps = header.bitrateBps,
            transfer = header.transfer,
            colorRange = header.colorRange,
            bitDepth = header.bitDepth,
            videoTrackCount = header.videoTrackCount,
            audioTrackCount = header.audioTrackCount,
            otherTrackCount = header.otherTrackCount,
            dateTakenEpochMs = header.dateTakenEpochMs,
            lastModifiedEpochMs = header.fingerprint.lastModifiedEpochMs,
            fingerprint = header.fingerprint,
        )
    }
}

public data class ScanReport(
    val videos: List<Video>,
    val alreadyProcessed: List<StorageRef>,
    val unreadable: List<UnreadableFile>,
    val permissionDeniedFolders: List<FolderId>,
    val failedFolders: List<FolderFailure>,
) {
    val scannedBytes: Long get() = videos.sumOf { it.sizeBytes }
}

public data class UnreadableFile(val ref: StorageRef, val detail: String)

public data class FolderFailure(val folder: FolderId, val detail: String)
