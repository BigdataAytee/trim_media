package dev.trim.ports.fake

import dev.trim.model.FolderId
import dev.trim.model.SourceFingerprint
import dev.trim.model.StorageRef
import dev.trim.model.TempRef
import dev.trim.ports.MoveResult
import dev.trim.ports.OriginalDestination
import dev.trim.ports.Storage
import dev.trim.ports.StorageListResult
import dev.trim.ports.StorageWriteResult

/** Every operation the fake can be told to fail, delay, or interrupt. */
public enum class StorageOp {
    LIST_VIDEOS,
    CREATE_TEMP,
    COPY_METADATA,
    MOVE_ORIGINAL,
    PROMOTE_TEMP,
    RESTORE_TIMESTAMPS,
    TRIGGER_MEDIA_SCAN,
    MOVE_BACK,
    DELETE_WRITTEN,
}

/**
 * An in-memory filesystem with a journal and injectable failures.
 *
 * The journal and [snapshot] are what make the Replacer kill-tests meaningful: a test
 * fails the sequence at step N and asserts the snapshot equals the one taken before the
 * sequence started — not "looks about right", but byte-for-byte the prior state.
 */
public class FakeStorage(
    private val clock: FakeClock = FakeClock(),
) : Storage {

    public data class Entry(
        val bytes: Long,
        val lastModifiedEpochMs: Long,
        val dateTakenEpochMs: Long?,
        val hash: String,
        val volume: String,
    )

    public data class Snapshot(
        val files: Map<String, Entry>,
        val temps: Map<String, Entry>,
    )

    private data class Failure(val detail: String, var remaining: Int, var skip: Int)

    public val files: LinkedHashMap<String, Entry> = LinkedHashMap()
    public val temps: LinkedHashMap<String, Entry> = LinkedHashMap()
    private val folders: LinkedHashMap<String, MutableList<String>> = LinkedHashMap()
    private val failures: MutableMap<StorageOp, Failure> = mutableMapOf()
    private val delays: MutableMap<StorageOp, Long> = mutableMapOf()

    /** Every mutation, in order, as a readable line. */
    public val journal: MutableList<String> = mutableListOf()

    /** Refs the media scanner was told about. */
    public val mediaScans: MutableList<String> = mutableListOf()

    /** Called before every operation, so a test can change the world mid-sequence. */
    public var beforeOp: (StorageOp) -> Unit = {}

    public var listPermissionDenied: Boolean = false
    public var freeSpaceOverride: Long = 64L * 1024 * 1024 * 1024
    private var nextTemp = 0

    // ---- world building ----

    public fun addFile(
        folder: FolderId,
        ref: StorageRef,
        bytes: Long,
        lastModifiedEpochMs: Long = clock.nowEpochMs(),
        dateTakenEpochMs: Long? = lastModifiedEpochMs,
        hash: String = "hash-${ref.value}",
        volume: String = "internal",
    ) {
        files[ref.value] = Entry(bytes, lastModifiedEpochMs, dateTakenEpochMs, hash, volume)
        folders.getOrPut(folder.value) { mutableListOf() }.add(ref.value)
    }

    /** Simulates another app editing the file: new hash, new stamp, same ref. */
    public fun touch(ref: StorageRef, bytesDelta: Long = 1, hashSuffix: String = "-edited") {
        val entry = files.getValue(ref.value)
        files[ref.value] = entry.copy(
            bytes = entry.bytes + bytesDelta,
            lastModifiedEpochMs = entry.lastModifiedEpochMs + 1_000,
            hash = entry.hash + hashSuffix,
        )
        journal += "touch ${ref.value}"
    }

    public fun snapshot(): Snapshot = Snapshot(LinkedHashMap(files), LinkedHashMap(temps))

    // ---- scripting ----

    /** Fail [op] the next [times] times it is called, after letting [skip] calls through. */
    public fun failOn(op: StorageOp, detail: String = "injected", times: Int = 1, skip: Int = 0) {
        failures[op] = Failure(detail, times, skip)
    }

    public fun clearFailures() {
        failures.clear()
    }

    public fun delayOn(op: StorageOp, millis: Long) {
        delays[op] = millis
    }

    private suspend fun enter(op: StorageOp): String? {
        beforeOp(op)
        delays[op]?.let { clock.sleep(it) }
        val failure = failures[op] ?: return null
        if (failure.skip > 0) {
            failure.skip--
            return null
        }
        if (failure.remaining <= 0) return null
        failure.remaining--
        journal += "FAIL $op (${failure.detail})"
        return failure.detail
    }

    // ---- read-only ----

    override suspend fun listVideos(folder: FolderId): StorageListResult {
        enter(StorageOp.LIST_VIDEOS)?.let { return StorageListResult.Failed(it) }
        if (listPermissionDenied) return StorageListResult.PermissionDenied
        val refs = folders[folder.value].orEmpty().filter { it in files }.map { StorageRef(it) }
        return StorageListResult.Listed(refs)
    }

    override suspend fun grantedFolders(): List<FolderId> = folders.keys.map { FolderId(it) }

    override suspend fun fingerprint(ref: StorageRef): SourceFingerprint? =
        files[ref.value]?.let { SourceFingerprint(it.bytes, it.lastModifiedEpochMs, it.hash) }

    override suspend fun exists(ref: StorageRef): Boolean = ref.value in files

    override suspend fun sizeBytes(ref: StorageRef): Long? = files[ref.value]?.bytes

    override suspend fun freeSpaceBytes(ref: StorageRef): Long = freeSpaceOverride

    override suspend fun volumeOf(ref: StorageRef): String =
        files[ref.value]?.volume ?: "internal"

    // ---- scratch space ----

    override suspend fun createTemp(hint: String): TempRef {
        enter(StorageOp.CREATE_TEMP)
        val ref = TempRef("temp://${nextTemp++}-$hint")
        temps[ref.value] = Entry(0, clock.nowEpochMs(), null, "temp", "internal")
        journal += "createTemp ${ref.value}"
        return ref
    }

    override suspend fun deleteTemp(ref: TempRef) {
        if (temps.remove(ref.value) != null) journal += "deleteTemp ${ref.value}"
    }

    override suspend fun tempSizeBytes(ref: TempRef): Long? = temps[ref.value]?.bytes

    /** Used by the codec fake to record what it "wrote". Not part of the port. */
    public fun writeTemp(ref: TempRef, bytes: Long) {
        val entry = temps[ref.value] ?: error("temp ${ref.value} was never created")
        temps[ref.value] = entry.copy(bytes = bytes, lastModifiedEpochMs = clock.nowEpochMs())
    }

    // ---- write-capable: only the Replacer may call these ----

    override suspend fun copyMetadata(from: StorageRef, to: TempRef): StorageWriteResult {
        enter(StorageOp.COPY_METADATA)?.let { return StorageWriteResult.Failed(it) }
        val source = files[from.value] ?: return StorageWriteResult.Failed("no such file: $from")
        val temp = temps[to.value] ?: return StorageWriteResult.Failed("no such temp: $to")
        temps[to.value] = temp.copy(dateTakenEpochMs = source.dateTakenEpochMs)
        journal += "copyMetadata ${from.value} -> ${to.value}"
        return StorageWriteResult.Written
    }

    override suspend fun moveOriginal(
        ref: StorageRef,
        destination: OriginalDestination,
    ): MoveResult {
        enter(StorageOp.MOVE_ORIGINAL)?.let { return MoveResult.Failed(it) }
        val entry = files[ref.value] ?: return MoveResult.Failed("no such file: $ref")
        val target = when (destination) {
            OriginalDestination.UndoBin -> StorageRef("bin://${ref.value.substringAfterLast('/')}")
            is OriginalDestination.OffloadVolume ->
                StorageRef("${destination.volumeLabel}://${ref.value.substringAfterLast('/')}")
            OriginalDestination.Trash -> StorageRef("trash://${ref.value.substringAfterLast('/')}")
        }
        files.remove(ref.value)
        files[target.value] = when (destination) {
            is OriginalDestination.OffloadVolume -> entry.copy(volume = destination.volumeLabel)
            else -> entry
        }
        journal += "moveOriginal ${ref.value} -> ${target.value}"
        return MoveResult.Moved(target)
    }

    override suspend fun promoteTemp(temp: TempRef, to: StorageRef): StorageWriteResult {
        enter(StorageOp.PROMOTE_TEMP)?.let { return StorageWriteResult.Failed(it) }
        val entry = temps.remove(temp.value)
            ?: return StorageWriteResult.Failed("no such temp: $temp")
        files[to.value] = entry.copy(hash = "hash-compressed-${to.value}", volume = "internal")
        journal += "promoteTemp ${temp.value} -> ${to.value}"
        return StorageWriteResult.Written
    }

    override suspend fun restoreTimestamps(
        ref: StorageRef,
        lastModifiedEpochMs: Long,
        dateTakenEpochMs: Long?,
    ): StorageWriteResult {
        enter(StorageOp.RESTORE_TIMESTAMPS)?.let { return StorageWriteResult.Failed(it) }
        val entry = files[ref.value] ?: return StorageWriteResult.Failed("no such file: $ref")
        files[ref.value] = entry.copy(
            lastModifiedEpochMs = lastModifiedEpochMs,
            dateTakenEpochMs = dateTakenEpochMs,
        )
        journal += "restoreTimestamps ${ref.value}"
        return StorageWriteResult.Written
    }

    override suspend fun triggerMediaScan(ref: StorageRef): StorageWriteResult {
        enter(StorageOp.TRIGGER_MEDIA_SCAN)?.let { return StorageWriteResult.Failed(it) }
        mediaScans += ref.value
        journal += "mediaScan ${ref.value}"
        return StorageWriteResult.Written
    }

    override suspend fun moveBack(from: StorageRef, to: StorageRef): MoveResult {
        enter(StorageOp.MOVE_BACK)?.let { return MoveResult.Failed(it) }
        val entry = files.remove(from.value) ?: return MoveResult.Failed("no such file: $from")
        files[to.value] = entry
        journal += "moveBack ${from.value} -> ${to.value}"
        return MoveResult.Moved(to)
    }

    override suspend fun deleteWritten(ref: StorageRef): StorageWriteResult {
        enter(StorageOp.DELETE_WRITTEN)?.let { return StorageWriteResult.Failed(it) }
        files.remove(ref.value) ?: return StorageWriteResult.Failed("no such file: $ref")
        journal += "deleteWritten ${ref.value}"
        return StorageWriteResult.Written
    }
}
