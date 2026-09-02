package dev.trim.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.trim.data.db.TrimDatabase
import dev.trim.model.Candidate
import dev.trim.model.ColorRange
import dev.trim.model.EncodeSetting
import dev.trim.model.EstimateConfidence
import dev.trim.model.EstimateRange
import dev.trim.model.FolderId
import dev.trim.model.HistoryEntry
import dev.trim.model.Job
import dev.trim.model.JobId
import dev.trim.model.JobState
import dev.trim.model.JobTrigger
import dev.trim.model.OriginalFate
import dev.trim.model.OutputCodec
import dev.trim.model.QualityTarget
import dev.trim.model.RejectionReason
import dev.trim.model.SkippedEntry
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.TransferFunction
import dev.trim.model.TrimSettings
import dev.trim.model.UndoEntry
import dev.trim.model.Video
import dev.trim.model.VideoCodec
import dev.trim.model.VideoId
import dev.trim.pipeline.replace.UndoJournal
import dev.trim.pipeline.replace.UndoWriteResult
import dev.trim.pipeline.scan.ProcessedLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext

/** Scanned files. The UI never asks the pipeline anything; it renders these rows. */
public class VideoDao(
    private val db: TrimDatabase,
    private val context: CoroutineContext,
) {
    public fun upsert(video: Video, scannedAtEpochMs: Long) {
        db.videosQueries.upsert(video.toRow(scannedAtEpochMs))
    }

    public fun upsertAll(videos: List<Video>, scannedAtEpochMs: Long) {
        db.transaction { videos.forEach { upsert(it, scannedAtEpochMs) } }
    }

    public fun byRef(ref: StorageRef): Video? =
        db.videosQueries.selectByRef(ref.value).executeAsOneOrNull()?.toVideo()

    public fun all(): List<Video> = db.videosQueries.selectAll().executeAsList().map { it.toVideo() }

    public fun observeAll(): Flow<List<Video>> =
        db.videosQueries.selectAll().asFlow().mapToList(context).map { rows ->
            rows.map { it.toVideo() }
        }

    public fun delete(ref: StorageRef) {
        db.videosQueries.deleteByRef(ref.value)
    }
}

/** Triage's verdicts — both of them. The rejections are as much a projection as the rest. */
public class CandidateDao(
    private val db: TrimDatabase,
    private val context: CoroutineContext,
) {
    public fun recordCandidate(candidate: Candidate, judgedAtEpochMs: Long) {
        db.candidatesQueries.upsert(
            dev.trim.data.db.Candidates(
                video_ref = candidate.video.ref.value,
                is_candidate = 1,
                estimate_low = candidate.estimate.lowBytes,
                estimate_high = candidate.estimate.highBytes,
                estimate_confidence = candidate.estimate.confidence.name,
                bracket_safest = candidate.bracket.safest.quality.toLong(),
                bracket_aggressive = candidate.bracket.mostAggressive.quality.toLong(),
                predicted_quality = candidate.predictedSetting?.quality?.toLong(),
                reason_type = null,
                reason_detail = null,
                judged_at_epoch_ms = judgedAtEpochMs,
            ),
        )
    }

    public fun recordRejection(
        ref: StorageRef,
        reason: RejectionReason,
        judgedAtEpochMs: Long,
    ) {
        db.candidatesQueries.upsert(
            dev.trim.data.db.Candidates(
                video_ref = ref.value,
                is_candidate = 0,
                estimate_low = null,
                estimate_high = null,
                estimate_confidence = null,
                bracket_safest = null,
                bracket_aggressive = null,
                predicted_quality = null,
                reason_type = Reasons.encodeType(reason),
                reason_detail = Reasons.encodeDetail(reason),
                judged_at_epoch_ms = judgedAtEpochMs,
            ),
        )
    }

    /** Sorted by estimated saving, descending — the Hub's order (frontend §5). */
    public fun observeShrinkable(): Flow<List<CandidateRow>> =
        db.candidatesQueries.selectShrinkable().asFlow().mapToList(context).map { rows ->
            rows.map { row ->
                CandidateRow(
                    ref = StorageRef(row.video_ref),
                    displayName = row.display_name,
                    sizeBytes = row.size_bytes,
                    estimate = EstimateRange(
                        lowBytes = row.estimate_low!!,
                        highBytes = row.estimate_high!!,
                        confidence = EstimateConfidence.valueOf(row.estimate_confidence!!),
                    ),
                    predictedSetting = row.predicted_quality?.let {
                        EncodeSetting(it.toInt(), OutputCodec.HEVC)
                    },
                )
            }
        }

    public fun observeNotShrinkable(): Flow<List<SkippedRow>> =
        db.candidatesQueries.selectNotShrinkable().asFlow().mapToList(context).map { rows ->
            rows.mapNotNull { row ->
                val reason = Reasons.decode(row.reason_type!!, row.reason_detail.orEmpty())
                reason?.let {
                    SkippedRow(StorageRef(row.video_ref), row.display_name, row.size_bytes, it)
                }
            }
        }
}

public data class CandidateRow(
    val ref: StorageRef,
    val displayName: String,
    val sizeBytes: Long,
    val estimate: EstimateRange,
    val predictedSetting: EncodeSetting?,
)

public data class SkippedRow(
    val ref: StorageRef,
    val displayName: String,
    val sizeBytes: Long,
    val reason: RejectionReason,
)

/**
 * The queue. [claim] is the single statement that makes double-running impossible: only a
 * QUEUED row can be taken, so two processes racing produce one winner and one no-op.
 */
public class JobDao(private val db: TrimDatabase) {

    public fun enqueue(job: Job) {
        db.jobsQueries.enqueue(
            dev.trim.data.db.Jobs(
                id = job.id.value,
                video_ref = job.videoId.value,
                trigger_kind = job.trigger.name,
                state = job.state.name,
                queued_at_epoch_ms = job.queuedAtEpochMs,
                claimed_at_epoch_ms = job.claimedAtEpochMs,
                claim_token = job.claimToken,
            ),
        )
    }

    public fun queued(): List<Job> =
        db.jobsQueries.selectQueued().executeAsList().map { it.toJob() }

    public fun running(): List<Job> =
        db.jobsQueries.selectRunning().executeAsList().map { it.toJob() }

    /** True if this process now owns the job. False means somebody else got there first. */
    public fun claim(id: JobId, token: String, nowEpochMs: Long): Boolean =
        db.transactionWithResult {
            db.jobsQueries.claim(nowEpochMs, token, id.value)
            db.jobsQueries.changes().executeAsOne() == 1L
        }

    public fun release(id: JobId) {
        db.jobsQueries.release(id.value)
    }

    public fun finish(id: JobId, state: JobState) {
        require(state != JobState.QUEUED && state != JobState.RUNNING) {
            "finish() records a terminal state; use release() to put a job back"
        }
        db.jobsQueries.finish(state.name, id.value)
    }

    /**
     * Startup repair: any claim not held by this process, or older than this process, was
     * made by a process that is gone. Its job goes back on the queue rather than sitting
     * RUNNING forever (DECISIONS D6.4).
     */
    public fun releaseStaleClaims(thisProcessToken: String, processStartedAtEpochMs: Long): Int =
        db.transactionWithResult {
            db.jobsQueries.releaseStaleClaims(thisProcessToken, processStartedAtEpochMs)
            db.jobsQueries.changes().executeAsOne().toInt()
        }
}

/** History, the skipped list, and the processed gate that prevents generational loss. */
public class HistoryDao(
    private val db: TrimDatabase,
    private val context: CoroutineContext,
) : ProcessedLedger {

    public fun recordCompleted(entry: HistoryEntry) {
        db.historyQueries.recordCompleted(
            dev.trim.data.db.History(
                video_ref = entry.videoId.value,
                display_name = entry.displayName,
                completed_at_epoch_ms = entry.completedAtEpochMs,
                original_bytes = entry.originalBytes,
                compressed_bytes = entry.compressedBytes,
                fate_kind = entry.originalFate.kind(),
                fate_detail = entry.originalFate.detail(),
                restorable_until_epoch_ms = entry.restorableUntilEpochMs,
            ),
        )
    }

    public fun recordSkipped(entry: SkippedEntry) {
        db.historyQueries.recordSkipped(
            dev.trim.data.db.Skipped(
                video_ref = entry.videoId.value,
                display_name = entry.displayName,
                recorded_at_epoch_ms = entry.recordedAtEpochMs,
                reason_type = Reasons.encodeType(entry.reason),
                reason_detail = Reasons.encodeDetail(entry.reason),
            ),
        )
    }

    public fun history(): List<HistoryEntry> =
        db.historyQueries.selectHistory().executeAsList().map { it.toEntry() }

    public fun observeHistory(): Flow<List<HistoryEntry>> =
        db.historyQueries.selectHistory().asFlow().mapToList(context).map { rows ->
            rows.map { it.toEntry() }
        }

    public fun skipped(): List<SkippedEntry> =
        db.historyQueries.selectSkipped().executeAsList().mapNotNull { row ->
            Reasons.decode(row.reason_type, row.reason_detail)?.let { reason ->
                SkippedEntry(
                    videoId = VideoId(row.video_ref),
                    displayName = row.display_name,
                    recordedAtEpochMs = row.recorded_at_epoch_ms,
                    reason = reason,
                )
            }
        }

    public fun lifetimeSavedBytes(): Long = db.historyQueries.lifetimeSaved().executeAsOne()

    override suspend fun isProcessed(ref: StorageRef): Boolean =
        db.historyQueries.isProcessed(ref.value).executeAsOne()

    public fun forget(videoId: VideoId) {
        db.historyQueries.deleteHistory(videoId.value)
    }
}

/** The durable [UndoJournal]. Rows first, files second. */
public class UndoDao(
    private val db: TrimDatabase,
    private val context: CoroutineContext,
) : UndoJournal {

    override suspend fun record(entry: UndoEntry): UndoWriteResult = runCatching {
        db.undoEntriesQueries.record(
            dev.trim.data.db.Undo_entries(
                video_ref = entry.videoId.value,
                original_ref = entry.originalRef.value,
                bin_ref = entry.binRef.value,
                compressed_ref = entry.compressedRef.value,
                compressed_size = entry.compressedFingerprint.sizeBytes,
                compressed_modified = entry.compressedFingerprint.lastModifiedEpochMs,
                compressed_hash = entry.compressedFingerprint.headTailHash,
                created_at_epoch_ms = entry.createdAtEpochMs,
                expires_at_epoch_ms = entry.expiresAtEpochMs,
            ),
        )
    }.fold(
        onSuccess = { UndoWriteResult.Written },
        onFailure = { UndoWriteResult.Failed(it.message ?: "undo entry could not be written") },
    )

    override suspend fun forget(videoId: VideoId): Boolean = db.transactionWithResult {
        db.undoEntriesQueries.forget(videoId.value)
        db.undoEntriesQueries.changes().executeAsOne() == 1L
    }

    public fun all(): List<UndoEntry> =
        db.undoEntriesQueries.selectAll().executeAsList().map { it.toEntry() }

    public fun byId(videoId: VideoId): UndoEntry? =
        db.undoEntriesQueries.selectByRef(videoId.value).executeAsOneOrNull()?.toEntry()

    public fun expired(nowEpochMs: Long): List<UndoEntry> =
        db.undoEntriesQueries.selectExpired(nowEpochMs).executeAsList().map { it.toEntry() }

    public fun observeAll(): Flow<List<UndoEntry>> =
        db.undoEntriesQueries.selectAll().asFlow().mapToList(context).map { rows ->
            rows.map { it.toEntry() }
        }
}

/** Folder modes and the single settings row. */
public class SettingsDao(
    private val db: TrimDatabase,
    private val context: CoroutineContext,
) {
    public fun setFolderMode(
        folder: FolderId,
        fate: OriginalFate,
        includeInNightly: Boolean,
        deleteConfirmed: Boolean,
    ) {
        db.settingsQueries.upsertFolderMode(
            dev.trim.data.db.Folder_modes(
                folder_id = folder.value,
                fate_kind = fate.kind(),
                fate_detail = fate.detail(),
                include_in_nightly = if (includeInNightly) 1 else 0,
                delete_confirmed = if (deleteConfirmed) 1 else 0,
            ),
        )
    }

    public fun folderMode(folder: FolderId): dev.trim.model.FolderMode? =
        db.settingsQueries.selectFolderMode(folder.value).executeAsOneOrNull()?.let { row ->
            dev.trim.model.FolderMode(
                folderId = FolderId(row.folder_id),
                fate = fateOf(row.fate_kind, row.fate_detail),
                includeInNightly = row.include_in_nightly == 1L,
            )
        }

    public fun save(settings: TrimSettings) {
        db.settingsQueries.upsertSettings(
            dev.trim.data.db.Settings(
                id = 0,
                quality_target = settings.qualityTarget.name,
                nightly_enabled = if (settings.nightlyEnabled) 1 else 0,
                require_full_charge = if (settings.requireFullCharge) 1 else 0,
                stop_before_alarm = if (settings.stopBeforeAlarm) 1 else 0,
                nightly_byte_cap = settings.nightlyByteCap,
                work_while_using_phone = if (settings.workWhileUsingPhone) 1 else 0,
                default_fate_kind = settings.defaultOriginalFate.kind(),
                default_fate_detail = settings.defaultOriginalFate.detail(),
            ),
        )
    }

    public fun load(): TrimSettings =
        db.settingsQueries.selectSettings().executeAsOneOrNull()?.toSettings()
            ?: TrimSettings.DEFAULT

    public fun observe(): Flow<TrimSettings> =
        db.settingsQueries.selectSettings().asFlow().mapToOneOrNull(context).map { row ->
            row?.toSettings() ?: TrimSettings.DEFAULT
        }
}

// ---- row mapping ----

private fun Video.toRow(scannedAtEpochMs: Long) = dev.trim.data.db.Videos(
    ref = ref.value,
    folder_id = folderId.value,
    display_name = displayName,
    size_bytes = sizeBytes,
    duration_ms = durationMs,
    width = width.toLong(),
    height = height.toLong(),
    frame_rate = frameRate,
    codec = codec.name,
    bitrate_bps = bitrateBps,
    transfer = transfer.name,
    color_range = colorRange.name,
    bit_depth = bitDepth.toLong(),
    video_track_count = videoTrackCount.toLong(),
    audio_track_count = audioTrackCount.toLong(),
    other_track_count = otherTrackCount.toLong(),
    date_taken_epoch_ms = dateTakenEpochMs,
    last_modified_epoch_ms = lastModifiedEpochMs,
    fingerprint_size = fingerprint.sizeBytes,
    fingerprint_modified = fingerprint.lastModifiedEpochMs,
    fingerprint_hash = fingerprint.headTailHash,
    scanned_at_epoch_ms = scannedAtEpochMs,
)

private fun dev.trim.data.db.Videos.toVideo() = Video(
    id = VideoId(ref),
    folderId = FolderId(folder_id),
    ref = StorageRef(ref),
    displayName = display_name,
    sizeBytes = size_bytes,
    durationMs = duration_ms,
    width = width.toInt(),
    height = height.toInt(),
    frameRate = frame_rate,
    codec = VideoCodec.valueOf(codec),
    bitrateBps = bitrate_bps,
    transfer = TransferFunction.valueOf(transfer),
    colorRange = ColorRange.valueOf(color_range),
    bitDepth = bit_depth.toInt(),
    videoTrackCount = video_track_count.toInt(),
    audioTrackCount = audio_track_count.toInt(),
    otherTrackCount = other_track_count.toInt(),
    dateTakenEpochMs = date_taken_epoch_ms,
    lastModifiedEpochMs = last_modified_epoch_ms,
    fingerprint = SourceFingerprint(fingerprint_size, fingerprint_modified, fingerprint_hash),
)

private fun dev.trim.data.db.Jobs.toJob() = Job(
    id = JobId(id),
    videoId = VideoId(video_ref),
    trigger = JobTrigger.valueOf(trigger_kind),
    state = JobState.valueOf(state),
    queuedAtEpochMs = queued_at_epoch_ms,
    claimedAtEpochMs = claimed_at_epoch_ms,
    claimToken = claim_token,
)

private fun dev.trim.data.db.History.toEntry() = HistoryEntry(
    videoId = VideoId(video_ref),
    displayName = display_name,
    completedAtEpochMs = completed_at_epoch_ms,
    originalBytes = original_bytes,
    compressedBytes = compressed_bytes,
    originalFate = fateOf(fate_kind, fate_detail),
    restorableUntilEpochMs = restorable_until_epoch_ms,
)

private fun dev.trim.data.db.Undo_entries.toEntry() = UndoEntry(
    videoId = VideoId(video_ref),
    originalRef = StorageRef(original_ref),
    binRef = StorageRef(bin_ref),
    compressedRef = StorageRef(compressed_ref),
    compressedFingerprint = SourceFingerprint(
        compressed_size,
        compressed_modified,
        compressed_hash,
    ),
    createdAtEpochMs = created_at_epoch_ms,
    expiresAtEpochMs = expires_at_epoch_ms,
)

private fun dev.trim.data.db.SelectExpired.toEntry() = UndoEntry(
    videoId = VideoId(video_ref),
    originalRef = StorageRef(original_ref),
    binRef = StorageRef(bin_ref),
    compressedRef = StorageRef(compressed_ref),
    compressedFingerprint = SourceFingerprint(
        compressed_size,
        compressed_modified,
        compressed_hash,
    ),
    createdAtEpochMs = created_at_epoch_ms,
    expiresAtEpochMs = expires_at_epoch_ms,
)

private fun dev.trim.data.db.Settings.toSettings() = TrimSettings(
    qualityTarget = QualityTarget.valueOf(quality_target),
    nightlyEnabled = nightly_enabled == 1L,
    requireFullCharge = require_full_charge == 1L,
    stopBeforeAlarm = stop_before_alarm == 1L,
    nightlyByteCap = nightly_byte_cap,
    workWhileUsingPhone = work_while_using_phone == 1L,
    defaultOriginalFate = fateOf(default_fate_kind, default_fate_detail),
)

internal fun OriginalFate.kind(): String = when (this) {
    is OriginalFate.KeptDays -> "kept"
    is OriginalFate.Offloaded -> "offloaded"
    OriginalFate.Deleted -> "deleted"
}

internal fun OriginalFate.detail(): String? = when (this) {
    is OriginalFate.KeptDays -> days.toString()
    is OriginalFate.Offloaded -> volumeLabel
    OriginalFate.Deleted -> null
}

internal fun fateOf(kind: String, detail: String?): OriginalFate = when (kind) {
    "kept" -> OriginalFate.KeptDays(detail?.toIntOrNull() ?: 1)
    "offloaded" -> OriginalFate.Offloaded(detail ?: "removable volume")
    else -> OriginalFate.Deleted
}
