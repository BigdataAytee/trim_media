package dev.trim.model

/**
 * What happens to the original file after a successful replace. Every completion message
 * states one of these (frontend-architecture §4.2), so there is no "compressed!" without
 * an answer to "and where did my file go?".
 */
public sealed interface OriginalFate {

    public val displayText: String

    /** Moved to the undo bin and restorable for [days]. */
    public data class KeptDays(val days: Int) : OriginalFate {
        init {
            require(days >= 1) { "KeptDays must be at least one day; zero days is Deleted" }
        }

        override val displayText: String = "kept $days days"
    }

    /** Moved off the internal volume to [volumeLabel] — an SD card or USB volume. */
    public data class Offloaded(val volumeLabel: String) : OriginalFate {
        init {
            require(volumeLabel.isNotBlank()) { "volumeLabel must not be blank" }
        }

        override val displayText: String = "moved to $volumeLabel"
    }

    /** Deleted immediately. Requires the one-time per-folder confirmation. */
    public data object Deleted : OriginalFate {
        override val displayText: String = "deleted"
    }
}

/** How a folder's originals are handled by default. */
public data class FolderMode(
    val folderId: FolderId,
    val fate: OriginalFate,
    val includeInNightly: Boolean,
)
