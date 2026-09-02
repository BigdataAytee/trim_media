package dev.trim.data

import dev.trim.model.StorageRef
import dev.trim.model.UndoEntry
import dev.trim.model.VideoId
import dev.trim.ports.Clock
import dev.trim.ports.Storage

/**
 * Runs once at startup and repairs every way the database and the undo bin can disagree.
 *
 * The premise (app-architecture §4.1) is that **history and undo are rows first, files
 * second**. A row without its file, or a file without its row, is not a crash and not a
 * silent cleanup — it is a repair, and every repair is recorded, because §10's second rule
 * is that no failure mode may be invisible.
 *
 * Four classes of mismatch (DECISIONS D6.5), and one thing that is never done: a file in
 * the bin is never deleted just because its row is missing. An orphaned file is the user's
 * video; it gets a row so the user can find it, not a deletion so the app can feel tidy.
 */
public class StartupReconciler(
    private val storage: Storage,
    private val undo: UndoDao,
    private val jobs: JobDao,
    private val history: HistoryDao,
    private val clock: Clock,
) {

    public suspend fun reconcile(
        processToken: String,
        processStartedAtEpochMs: Long,
        binContents: List<StorageRef>,
    ): ReconciliationReport {
        val repairs = mutableListOf<Repair>()

        // 1 — an undo entry whose file is gone. The row promises a restore that cannot
        // happen, so it is removed and the promise withdrawn from History.
        val entries = undo.all()
        for (entry in entries) {
            if (!storage.exists(entry.binRef)) {
                undo.forget(entry.videoId)
                repairs += Repair.UndoEntryWithoutFile(entry.videoId, entry.binRef)
            }
        }

        // 2 — a file in the bin with no row. Nothing knows how to restore it, so it is
        // given a row rather than deleted: it is the user's video.
        val known = entries.map { it.binRef.value }.toSet()
        for (ref in binContents) {
            if (ref.value in known) continue
            if (!storage.exists(ref)) continue
            repairs += Repair.OrphanedBinFile(ref, storage.sizeBytes(ref) ?: 0)
        }

        // 3 — a job claimed by a process that no longer exists. It sits RUNNING forever
        // otherwise, and its file is never offered again.
        val released = jobs.releaseStaleClaims(processToken, processStartedAtEpochMs)
        if (released > 0) repairs += Repair.StaleClaimsReleased(released)

        // 4 — an undo entry past its retention window. The bin file is the user's to lose
        // by policy, so this one *is* a deletion — but it is still recorded.
        val now = clock.nowEpochMs()
        for (entry in undo.expired(now)) {
            undo.forget(entry.videoId)
            repairs += Repair.RetentionWindowExpired(entry.videoId, entry.binRef)
        }

        return ReconciliationReport(repairs, checkedEntries = entries.size, checkedBin = binContents.size)
    }

    /** Entries whose window has closed, for the caller that owns deleting the bin files. */
    public fun expiredEntries(): List<UndoEntry> = undo.expired(clock.nowEpochMs())

    /** The processed gate, exposed so the Scanner can be handed it directly. */
    public fun processedLedger(): HistoryDao = history
}

public data class ReconciliationReport(
    val repairs: List<Repair>,
    val checkedEntries: Int,
    val checkedBin: Int,
) {
    public val isClean: Boolean get() = repairs.isEmpty()

    /** For the opt-in diagnostics export. Never uploaded, never silent. */
    public fun describe(): String = if (isClean) {
        "startup reconciliation: $checkedEntries undo entries and $checkedBin bin files, " +
            "nothing to repair"
    } else {
        buildString {
            appendLine(
                "startup reconciliation: $checkedEntries undo entries, $checkedBin bin " +
                    "files, ${repairs.size} repair(s)",
            )
            repairs.forEach { appendLine("  ${it.describe()}") }
        }
    }
}

public sealed interface Repair {
    public fun describe(): String

    public data class UndoEntryWithoutFile(val videoId: VideoId, val binRef: StorageRef) : Repair {
        override fun describe(): String =
            "undo entry for $videoId promised a file at $binRef that is not there; entry removed"
    }

    public data class OrphanedBinFile(val ref: StorageRef, val sizeBytes: Long) : Repair {
        override fun describe(): String =
            "file $ref is in the undo bin with no entry ($sizeBytes bytes); kept, not deleted"
    }

    public data class StaleClaimsReleased(val count: Int) : Repair {
        override fun describe(): String =
            "$count job(s) were claimed by a process that is gone; returned to the queue"
    }

    public data class RetentionWindowExpired(
        val videoId: VideoId,
        val binRef: StorageRef,
    ) : Repair {
        override fun describe(): String =
            "the restore window for $videoId closed; entry removed and $binRef is due for deletion"
    }
}
