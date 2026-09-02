package dev.trim.pipeline.replace

import dev.trim.model.RestoreRefusal
import dev.trim.model.RestoreResult
import dev.trim.model.UndoEntry
import dev.trim.model.VideoId
import dev.trim.ports.Clock
import dev.trim.ports.MoveResult
import dev.trim.ports.Storage
import dev.trim.ports.StorageWriteResult

/**
 * The mirror image of the [Replacer] (app-architecture §6), and — like it — one of the
 * only classes permitted to write to user storage, which is why it lives in this package
 * and is covered by the same build guard.
 *
 * It refuses to run if the compressed file has been edited by another app since Trim wrote
 * it, because putting the original back would then destroy somebody's work rather than
 * undo Trim's.
 *
 * Ordering, and what each failure costs:
 *
 * 1. remove the compressed file — the original's path must be free before anything moves
 * 2. move the original back from the bin
 * 3. restore the media scan, so the gallery sees the change
 * 4. forget the undo entry
 *
 * A failure at step 2 loses the compressed file but not the original, which is still in the
 * bin and still described by its row — the entry is deliberately *not* forgotten, so the
 * user can try again.
 */
public class Restorer(
    private val storage: Storage,
    private val clock: Clock,
    private val journal: UndoJournal,
) {

    public suspend fun restore(entry: UndoEntry): RestoreResult {
        val expiry = entry.expiresAtEpochMs
        if (expiry != null && clock.nowEpochMs() > expiry) {
            return RestoreResult.Refused(entry.videoId, RestoreRefusal.WindowExpired)
        }
        if (!storage.exists(entry.binRef)) {
            return RestoreResult.Refused(entry.videoId, RestoreRefusal.OriginalMissing)
        }

        val current = storage.fingerprint(entry.compressedRef)
        if (current != null && current != entry.compressedFingerprint) {
            return RestoreResult.Refused(entry.videoId, RestoreRefusal.CompressedFileModified)
        }

        if (current != null) {
            when (val removed = storage.deleteWritten(entry.compressedRef)) {
                is StorageWriteResult.Failed -> return RestoreResult.Refused(
                    entry.videoId,
                    RestoreRefusal.StorageRefused(removed.detail),
                )
                StorageWriteResult.Written -> Unit
            }
        }

        val restoredBytes = storage.sizeBytes(entry.binRef) ?: 0
        when (val moved = storage.moveBack(entry.binRef, entry.originalRef)) {
            is MoveResult.Failed -> return RestoreResult.Refused(
                entry.videoId,
                // The entry is left in place on purpose: the original is still in the bin,
                // and forgetting the row would strand it.
                RestoreRefusal.StorageRefused(moved.detail),
            )
            is MoveResult.Moved -> Unit
        }

        storage.triggerMediaScan(entry.originalRef)
        journal.forget(entry.videoId)
        return RestoreResult.Restored(entry.videoId, restoredBytes)
    }

    public suspend fun restore(
        videoId: VideoId,
        lookup: suspend (VideoId) -> UndoEntry?,
    ): RestoreResult {
        val entry = lookup(videoId)
            ?: return RestoreResult.Refused(videoId, RestoreRefusal.OriginalMissing)
        return restore(entry)
    }
}
