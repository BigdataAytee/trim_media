package dev.trim.ports

import dev.trim.model.FolderId
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.TempRef

/**
 * User storage and app scratch space.
 *
 * Every member that mutates user storage carries [StorageWrite] and may be called only
 * from the Replacer (app-architecture §6/§8). The annotation is what the build guard
 * reads, so this interface is the single place the rule is stated.
 */
public interface Storage {

    // ---- read-only: anyone may call ----

    /** The videos visible under a granted folder. Header-free; the Scanner probes after. */
    public suspend fun listVideos(folder: FolderId): StorageListResult

    /** The folders the user has granted, in grant order. */
    public suspend fun grantedFolders(): List<FolderId>

    /** Current identity snapshot of a file, or null if it is gone. */
    public suspend fun fingerprint(ref: StorageRef): SourceFingerprint?

    public suspend fun exists(ref: StorageRef): Boolean

    public suspend fun sizeBytes(ref: StorageRef): Long?

    /** Free space on the volume that holds [ref], for the out-of-space check. */
    public suspend fun freeSpaceBytes(ref: StorageRef): Long

    /** Which volume a ref lives on; the Replacer prefers same-volume moves (§6 step 2). */
    public suspend fun volumeOf(ref: StorageRef): String

    // ---- app-owned scratch space: not user storage, so not guarded ----

    public suspend fun createTemp(hint: String): TempRef

    public suspend fun deleteTemp(ref: TempRef)

    public suspend fun tempSizeBytes(ref: TempRef): Long?

    // ---- write-capable: Replacer only ----

    /** §6 step 1 — creation time, GPS, rotation, camera tags. */
    @StorageWrite
    public suspend fun copyMetadata(from: StorageRef, to: TempRef): StorageWriteResult

    /** §6 step 2 — the original leaves its place, to the bin, an offload volume, or the trash. */
    @StorageWrite
    public suspend fun moveOriginal(ref: StorageRef, destination: OriginalDestination): MoveResult

    /** §6 step 3 — the atomic point: the new file takes the original's path and name. */
    @StorageWrite
    public suspend fun promoteTemp(temp: TempRef, to: StorageRef): StorageWriteResult

    /** §6 step 4 — restore the original's last-modified stamp and DATE_TAKEN. */
    @StorageWrite
    public suspend fun restoreTimestamps(
        ref: StorageRef,
        lastModifiedEpochMs: Long,
        dateTakenEpochMs: Long?,
    ): StorageWriteResult

    /** §6 step 5 — tell the gallery. */
    @StorageWrite
    public suspend fun triggerMediaScan(ref: StorageRef): StorageWriteResult

    /** Rollback and restore: put a moved original back where it came from. */
    @StorageWrite
    public suspend fun moveBack(from: StorageRef, to: StorageRef): MoveResult

    /** Rollback: remove a file this app wrote to user storage. */
    @StorageWrite
    public suspend fun deleteWritten(ref: StorageRef): StorageWriteResult
}

public sealed interface StorageListResult {
    public data class Listed(val refs: List<StorageRef>) : StorageListResult

    /** The grant is gone — the UI shows NoFolderAccess rather than an empty gallery. */
    public data object PermissionDenied : StorageListResult

    public data class Failed(val detail: String) : StorageListResult
}

/** Where the original goes at §6 step 2. */
public sealed interface OriginalDestination {
    public data object UndoBin : OriginalDestination
    public data class OffloadVolume(val volumeLabel: String) : OriginalDestination
    public data object Trash : OriginalDestination
}

public sealed interface StorageWriteResult {
    public data object Written : StorageWriteResult
    public data class Failed(val detail: String) : StorageWriteResult
}

public sealed interface MoveResult {
    /** [to] is where the file now lives, so a rollback knows what to move back. */
    public data class Moved(val to: StorageRef) : MoveResult
    public data class Failed(val detail: String) : MoveResult
}
