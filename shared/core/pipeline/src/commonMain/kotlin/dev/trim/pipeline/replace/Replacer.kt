package dev.trim.pipeline.replace

import dev.trim.model.FailureReason
import dev.trim.model.OriginalFate
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.TempRef
import dev.trim.model.UndoEntry
import dev.trim.model.Video
import dev.trim.ports.Clock
import dev.trim.ports.MoveResult
import dev.trim.ports.OriginalDestination
import dev.trim.ports.Storage
import dev.trim.ports.StorageWriteResult

/**
 * **The only class in the application permitted to write to user storage.**
 *
 * That is not a convention — it is checked by the build (app-architecture §8, guard #3),
 * which reads the `@StorageWrite` annotations off the Storage port and fails if anything
 * outside this package calls one.
 *
 * The commit sequence of §6, in strict order:
 *
 * 1. copy metadata from the original into the new file
 * 2. move the original to its destination per folder mode
 * 3. rename the new file over the original's path and name
 * 4. restore the original's last-modified timestamp and DATE_TAKEN
 * 5. trigger the media scan
 * 6. write the undo entry
 *
 * Any failure rolls back, in reverse order, every step that had succeeded. Two invariants
 * fall out of the ordering and are what the kill-tests assert:
 *
 * - **The original is never lost.** It exists at every intermediate step — in place until
 *   step 2, in the undo bin (or offload volume) from step 2 onward.
 * - **Cancel is always safe.** Nothing the user can see changes until step 3, which is a
 *   single atomic rename.
 */
public class Replacer(
    private val storage: Storage,
    private val clock: Clock,
    private val journal: UndoJournal,
) {

    public suspend fun commit(request: ReplaceRequest): ReplaceResult {
        // Everything measured about this file was measured about a *particular* file. If it
        // is not that file any more, nothing downstream is true, and this must be found out
        // before the original moves — not after (DECISIONS D5.3).
        val current = storage.fingerprint(request.video.ref)
        if (current != request.video.fingerprint) {
            return ReplaceResult.Refused(
                FailureReason.SourceChanged(request.video.fingerprint, current),
            )
        }

        val done = mutableListOf<CompletedStep>()

        // 1 — metadata into the new file, before it becomes the user's file.
        when (val result = storage.copyMetadata(request.video.ref, request.temp)) {
            is StorageWriteResult.Failed ->
                return rollback(request, done, CommitStep.CopyMetadata, result.detail)
            StorageWriteResult.Written -> done += CompletedStep.MetadataCopied
        }

        // 2 — the original leaves its place. From here it lives at movedTo, and every
        // rollback path has to put it back.
        val movedTo = when (
            val result = storage.moveOriginal(request.video.ref, request.destination)
        ) {
            is MoveResult.Failed ->
                return rollback(request, done, CommitStep.MoveOriginal, result.detail)
            is MoveResult.Moved -> {
                done += CompletedStep.OriginalMoved(result.to)
                result.to
            }
        }

        // 3 — the atomic point. Before this line nothing the user can see has changed.
        when (val result = storage.promoteTemp(request.temp, request.video.ref)) {
            is StorageWriteResult.Failed ->
                return rollback(request, done, CommitStep.PromoteTemp, result.detail)
            StorageWriteResult.Written -> done += CompletedStep.TempPromoted
        }

        // 4 — galleries key off different fields, so both are restored.
        when (
            val result = storage.restoreTimestamps(
                ref = request.video.ref,
                lastModifiedEpochMs = request.video.lastModifiedEpochMs,
                dateTakenEpochMs = request.video.dateTakenEpochMs,
            )
        ) {
            is StorageWriteResult.Failed ->
                return rollback(request, done, CommitStep.RestoreTimestamps, result.detail)
            StorageWriteResult.Written -> done += CompletedStep.TimestampsRestored
        }

        // 5 — tell the gallery.
        when (val result = storage.triggerMediaScan(request.video.ref)) {
            is StorageWriteResult.Failed ->
                return rollback(request, done, CommitStep.TriggerMediaScan, result.detail)
            StorageWriteResult.Written -> done += CompletedStep.MediaScanned
        }

        // 6 — rows first, files second: without this row the undo bin is an orphan the
        // startup reconciler would have to clean up (app-architecture §4.1).
        val compressedFingerprint = storage.fingerprint(request.video.ref)
            ?: return rollback(
                request,
                done,
                CommitStep.WriteUndoEntry,
                "the committed file could not be fingerprinted",
            )
        val entry = UndoEntry(
            videoId = request.video.id,
            originalRef = request.video.ref,
            binRef = movedTo,
            compressedRef = request.video.ref,
            compressedFingerprint = compressedFingerprint,
            createdAtEpochMs = clock.nowEpochMs(),
            expiresAtEpochMs = expiryFor(request.fate),
        )
        when (val result = journal.record(entry)) {
            is UndoWriteResult.Failed ->
                return rollback(request, done, CommitStep.WriteUndoEntry, result.detail)
            UndoWriteResult.Written -> done += CompletedStep.UndoRecorded(entry)
        }

        return ReplaceResult.Committed(
            undoEntry = entry,
            compressedBytes = compressedFingerprint.sizeBytes,
        )
    }

    /**
     * Undoes [done] in reverse order. Rollback steps that themselves fail are recorded and
     * the rollback continues: giving up half way would be the one thing worse than the
     * failure that started it.
     */
    private suspend fun rollback(
        request: ReplaceRequest,
        done: List<CompletedStep>,
        failedAt: CommitStep,
        detail: String,
    ): ReplaceResult {
        val problems = mutableListOf<String>()

        for (step in done.asReversed()) {
            when (step) {
                is CompletedStep.UndoRecorded ->
                    if (!journal.forget(step.entry.videoId)) {
                        problems += "could not remove the undo entry for ${step.entry.videoId}"
                    }

                CompletedStep.MediaScanned -> Unit // a scan cannot be un-triggered; step 2's
                // rollback re-scans the restored original below.

                CompletedStep.TimestampsRestored -> Unit // the file they belong to is about
                // to be removed by the rollback of step 3.

                CompletedStep.TempPromoted ->
                    when (val result = storage.deleteWritten(request.video.ref)) {
                        is StorageWriteResult.Failed ->
                            problems += "could not remove the promoted file: ${result.detail}"
                        StorageWriteResult.Written -> Unit
                    }

                is CompletedStep.OriginalMoved -> {
                    when (val result = storage.moveBack(step.movedTo, request.video.ref)) {
                        is MoveResult.Failed ->
                            problems += "could not restore the original from " +
                                "${step.movedTo}: ${result.detail}"
                        is MoveResult.Moved -> storage.triggerMediaScan(request.video.ref)
                    }
                }

                CompletedStep.MetadataCopied -> Unit // it was copied into the temp file,
                // which never became the user's file.
            }
        }

        return ReplaceResult.RolledBack(
            failedStep = failedAt,
            detail = detail,
            rollbackProblems = problems,
        )
    }

    private fun expiryFor(fate: OriginalFate): Long? = when (fate) {
        is OriginalFate.KeptDays -> clock.nowEpochMs() + fate.days * MILLIS_PER_DAY
        is OriginalFate.Offloaded -> null
        OriginalFate.Deleted -> null
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/**
 * The six steps of app-architecture §6, named so that a kill-test can talk about a step
 * rather than a line number (DECISIONS D5.1). [ordinal] is the step's number in the
 * document, one-based.
 */
public enum class CommitStep(public val number: Int, public val description: String) {
    CopyMetadata(1, "copy metadata from the original into the new file"),
    MoveOriginal(2, "move the original to its destination"),
    PromoteTemp(3, "rename the new file over the original's path"),
    RestoreTimestamps(4, "restore the original's timestamps"),
    TriggerMediaScan(5, "trigger the media scan"),
    WriteUndoEntry(6, "write the undo entry"),
}

/** What has actually happened, carrying whatever the rollback of that step will need. */
private sealed interface CompletedStep {
    data object MetadataCopied : CompletedStep
    data class OriginalMoved(val movedTo: StorageRef) : CompletedStep
    data object TempPromoted : CompletedStep
    data object TimestampsRestored : CompletedStep
    data object MediaScanned : CompletedStep
    data class UndoRecorded(val entry: UndoEntry) : CompletedStep
}

public data class ReplaceRequest(
    val video: Video,
    val temp: TempRef,
    val fate: OriginalFate,
    val destination: OriginalDestination,
)

public sealed interface ReplaceResult {
    public data class Committed(
        val undoEntry: UndoEntry,
        val compressedBytes: Long,
    ) : ReplaceResult

    /**
     * The sequence failed and every completed step was undone. The original is where it
     * started. [rollbackProblems] is empty when the rollback itself was clean.
     */
    public data class RolledBack(
        val failedStep: CommitStep,
        val detail: String,
        val rollbackProblems: List<String>,
    ) : ReplaceResult {
        public fun toFailureReason(): FailureReason = FailureReason.ReplaceRolledBack(
            failedStep = failedStep.number,
            failedStepName = failedStep.description,
            detail = detail,
        )
    }

    /** The sequence never started, because the source was not the file we measured. */
    public data class Refused(val reason: FailureReason) : ReplaceResult
}

/**
 * Where undo entries live. Rows first, files second (app-architecture §4.1): the durable
 * implementation is in core/data, and the startup reconciler repairs any disagreement
 * between these rows and the bin.
 */
public interface UndoJournal {
    public suspend fun record(entry: UndoEntry): UndoWriteResult

    /** Removes an entry during rollback. Returns false if it could not be removed. */
    public suspend fun forget(videoId: dev.trim.model.VideoId): Boolean
}

public sealed interface UndoWriteResult {
    public data object Written : UndoWriteResult
    public data class Failed(val detail: String) : UndoWriteResult
}
