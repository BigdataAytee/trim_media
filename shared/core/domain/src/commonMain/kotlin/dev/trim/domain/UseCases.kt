package dev.trim.domain

import dev.trim.data.CandidateDao
import dev.trim.data.HistoryDao
import dev.trim.data.JobDao
import dev.trim.data.SettingsDao
import dev.trim.data.UndoDao
import dev.trim.data.VideoDao
import dev.trim.model.CompressPhase
import dev.trim.model.EstimateConfidence
import dev.trim.model.EstimateRange
import dev.trim.model.FolderId
import dev.trim.model.Job
import dev.trim.model.JobId
import dev.trim.model.JobOutcome
import dev.trim.model.JobState
import dev.trim.model.JobTrigger
import dev.trim.model.OriginalFate
import dev.trim.model.RestoreRefusal
import dev.trim.model.RestoreResult
import dev.trim.model.SkippedEntry
import dev.trim.model.TrimSettings
import dev.trim.model.TriageResult
import dev.trim.model.Video
import dev.trim.model.VideoId
import dev.trim.model.HistoryEntry
import dev.trim.model.sumEstimates
import dev.trim.pipeline.replace.Restorer
import dev.trim.pipeline.run.JobRequest
import dev.trim.pipeline.run.JobRunner
import dev.trim.pipeline.scan.ScanReport
import dev.trim.pipeline.scan.Scanner
import dev.trim.pipeline.triage.Triage
import dev.trim.ports.Clock
import dev.trim.ports.DeviceConditions
import dev.trim.ports.Scheduler
import dev.trim.ports.NightlyConstraints
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Scans, triages, and writes the result to the database. Nothing returns a candidate list
 * to a caller: the scan *writes rows*, and the Hub observes them (app-architecture §4.1,
 * "the hub never asks the pipeline anything directly — it renders the DB").
 */
public class ScanAndTriage(
    private val scanner: Scanner,
    private val triage: Triage,
    private val videos: VideoDao,
    private val candidates: CandidateDao,
    private val clock: Clock,
) {
    public suspend operator fun invoke(folders: List<FolderId>? = null): ScanReport {
        val report = scanner.scan(folders)
        val now = clock.nowEpochMs()
        videos.upsertAll(report.videos, now)
        for (video in report.videos) {
            when (val verdict = triage.judge(video)) {
                is TriageResult.Accepted -> candidates.recordCandidate(verdict.candidate, now)
                is TriageResult.Rejected ->
                    candidates.recordRejection(video.ref, verdict.reason, now)
            }
        }
        return report
    }
}

public class ObserveCandidatesImpl(
    private val candidates: CandidateDao,
) : ObserveCandidates {
    override fun invoke(): Flow<CandidateSnapshot> = combine(
        candidates.observeShrinkable(),
        candidates.observeNotShrinkable(),
    ) { shrinkable, notShrinkable ->
        CandidateSnapshot(
            shrinkable = shrinkable,
            notShrinkable = notShrinkable,
            totalFreeable = shrinkable
                .map { row ->
                    EstimateRange(
                        lowBytes = (row.sizeBytes - row.estimate.highBytes).coerceAtLeast(0),
                        highBytes = (row.sizeBytes - row.estimate.lowBytes).coerceAtLeast(0),
                        confidence = row.estimate.confidence,
                    )
                }
                .ifEmpty { listOf(EstimateRange.none(EstimateConfidence.PROBED)) }
                .sumEstimates(),
        )
    }
}

public class QueueForNightImpl(
    private val jobs: JobDao,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val settings: SettingsDao,
) : QueueForNight {
    override suspend fun invoke(ids: List<VideoId>) {
        val now = clock.nowEpochMs()
        ids.forEach { id ->
            jobs.enqueue(
                Job(
                    id = JobId("job-${id.value}-$now"),
                    videoId = id,
                    trigger = JobTrigger.NIGHTLY,
                    state = JobState.QUEUED,
                    queuedAtEpochMs = now,
                    claimedAtEpochMs = null,
                    claimToken = null,
                ),
            )
        }
        val current = settings.load()
        if (current.nightlyEnabled) {
            scheduler.scheduleNightly(
                NightlyConstraints.NIGHTLY.copy(
                    requiresFullCharge = current.requireFullCharge,
                    stopBeforeNextAlarm = current.stopBeforeAlarm,
                ),
            )
        }
    }
}

/**
 * Runs a queue of jobs and records their outcomes. This is where a run becomes *rows*:
 * a completed file gets a history row, a skipped or failed one gets a skipped row, and
 * both happen before the caller is told anything — so a process killed immediately after
 * a job still leaves a consistent database.
 */
public class RunQueue(
    private val runner: JobRunner,
    private val jobs: JobDao,
    private val videos: VideoDao,
    private val history: HistoryDao,
    private val undo: UndoDao,
    private val clock: Clock,
    private val processToken: String = "runner",
) {
    public suspend operator fun invoke(
        conditions: DeviceConditions,
        settings: TrimSettings,
        onPhase: (CompressPhase) -> Unit = {},
    ): List<JobOutcome> {
        val outcomes = mutableListOf<JobOutcome>()
        while (true) {
            val job = jobs.queued().firstOrNull() ?: break
            if (!jobs.claim(job.id, processToken, clock.nowEpochMs())) continue

            val video = videos.byRef(dev.trim.model.StorageRef(job.videoId.value))
            if (video == null) {
                jobs.finish(job.id, JobState.FAILED)
                continue
            }

            val outcome = runner.run(
                video = video,
                request = JobRequest(
                    trigger = job.trigger,
                    qualityTarget = settings.qualityTarget,
                    originalFate = settings.defaultOriginalFate,
                    conditions = conditions,
                    requireFullCharge = settings.requireFullCharge,
                    stopBeforeAlarm = settings.stopBeforeAlarm,
                    workWhileUsingPhone = settings.workWhileUsingPhone,
                    nightlyByteCap = settings.nightlyByteCap,
                ),
                onPhase = onPhase,
            )
            record(video, outcome)
            jobs.finish(job.id, outcome.toJobState())
            outcomes += outcome
        }
        return outcomes
    }

    private fun record(video: Video, outcome: JobOutcome) {
        when (outcome) {
            is JobOutcome.Compressed -> history.recordCompleted(
                HistoryEntry(
                    videoId = video.id,
                    displayName = video.displayName,
                    completedAtEpochMs = clock.nowEpochMs(),
                    originalBytes = outcome.result.originalBytes,
                    compressedBytes = outcome.result.compressedBytes,
                    originalFate = outcome.result.originalFate,
                    restorableUntilEpochMs = undo.byId(video.id)?.expiresAtEpochMs,
                ),
            )
            is JobOutcome.Skipped -> history.recordSkipped(
                SkippedEntry(video.id, video.displayName, clock.nowEpochMs(), outcome.reason),
            )
            is JobOutcome.Failed -> history.recordSkipped(
                SkippedEntry(video.id, video.displayName, clock.nowEpochMs(), outcome.reason),
            )
        }
    }
}

private fun JobOutcome.toJobState(): JobState = when (this) {
    is JobOutcome.Compressed -> JobState.DONE
    is JobOutcome.Skipped -> JobState.SKIPPED
    is JobOutcome.Failed -> JobState.FAILED
}

public class RestoreOriginalImpl(
    private val restorer: Restorer,
    private val undo: UndoDao,
    private val history: HistoryDao,
) : RestoreOriginal {
    override suspend fun invoke(id: VideoId): RestoreResult {
        val entry = undo.byId(id)
            ?: return RestoreResult.Refused(id, RestoreRefusal.OriginalMissing)
        val result = restorer.restore(entry)
        if (result is RestoreResult.Restored) history.forget(id)
        return result
    }
}

public class ObserveHistoryImpl(
    private val history: HistoryDao,
) : ObserveHistory {
    override fun invoke(): Flow<HistorySnapshot> = history.observeHistory().map { completed ->
        HistorySnapshot(
            completed = completed,
            skipped = history.skipped(),
            lifetimeSavedBytes = history.lifetimeSavedBytes(),
        )
    }
}

public class ObserveSettingsImpl(private val settings: SettingsDao) : ObserveSettings {
    override fun invoke(): Flow<TrimSettings> = settings.observe()
}

public class UpdateSettingsImpl(private val settings: SettingsDao) : UpdateSettings {
    override suspend fun invoke(settings: TrimSettings) {
        this.settings.save(settings)
    }
}

public class SetFolderModeImpl(private val settings: SettingsDao) : SetFolderMode {
    override suspend fun invoke(folder: FolderId, mode: OriginalFate) {
        settings.setFolderMode(
            folder = folder,
            fate = mode,
            includeInNightly = true,
            // The one-time confirmation for immediate deletion is the folders feature's
            // to own (frontend-architecture §5); this use case records the decision only.
            deleteConfirmed = mode is OriginalFate.Deleted,
        )
    }
}

/** Kept out of [RunQueue] so its tests stay free of coroutine plumbing. */
public fun progressFlow(block: suspend ((CompressPhase) -> Unit) -> Unit): Flow<CompressPhase> =
    callbackFlow {
        block { phase -> trySend(phase) }
        awaitClose { }
    }
