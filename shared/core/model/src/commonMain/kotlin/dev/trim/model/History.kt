package dev.trim.model

/** A completed compression, as History renders it. */
public data class HistoryEntry(
    val videoId: VideoId,
    val displayName: String,
    val completedAtEpochMs: Long,
    val originalBytes: Long,
    val compressedBytes: Long,
    val originalFate: OriginalFate,
    val restorableUntilEpochMs: Long?,
) {
    init {
        require(compressedBytes < originalBytes) { "a history entry must record a saving" }
    }

    public val savedBytes: Long get() = originalBytes - compressedBytes
}

/** A rejection, as History's skipped list renders it. */
public data class SkippedEntry(
    val videoId: VideoId,
    val displayName: String,
    val recordedAtEpochMs: Long,
    val reason: SkipReason,
)

/**
 * The row that makes an undo possible. Rows come first, files second: the undo bin is
 * reconciled against these on every start (app-architecture §4.1).
 */
public data class UndoEntry(
    val videoId: VideoId,
    val originalRef: StorageRef,
    val binRef: StorageRef,
    val compressedRef: StorageRef,
    val compressedFingerprint: SourceFingerprint,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
) {
    init {
        require(expiresAtEpochMs == null || expiresAtEpochMs > createdAtEpochMs) {
            "an undo entry cannot expire before it was created"
        }
    }
}

/** The result of asking for an original back. Refusal always says why. */
public sealed interface RestoreResult {
    public data class Restored(val videoId: VideoId, val restoredBytes: Long) : RestoreResult

    public data class Refused(val videoId: VideoId, val reason: RestoreRefusal) : RestoreResult
}

public sealed interface RestoreRefusal {
    public val displayText: String

    public data object WindowExpired : RestoreRefusal {
        override val displayText: String = "the original is no longer available to restore"
    }

    public data object OriginalMissing : RestoreRefusal {
        override val displayText: String = "the original couldn't be found"
    }

    /** The compressed file has been edited by another app since Trim wrote it. */
    public data object CompressedFileModified : RestoreRefusal {
        override val displayText: String =
            "the smaller version has been changed since Trim made it"
    }

    public data class StorageRefused(val detail: String) : RestoreRefusal {
        override val displayText: String = "couldn't move the original back"
    }
}
