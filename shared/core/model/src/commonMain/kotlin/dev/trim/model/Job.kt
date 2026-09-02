package dev.trim.model

/** How a job came to exist, which decides which constraints apply (app-architecture §7). */
public enum class JobTrigger {
    /** Queued for the nightly run: charging + idle, OS-enforced. */
    NIGHTLY,

    /** An explicit tap. Allowed on battery, best-effort codec priority. */
    USER_INITIATED,

    /** Arrived through the share sheet. Treated as user-initiated. */
    SHARE,
}

/** Where a job is in its life. Claiming is a transactional move to [RUNNING]. */
public enum class JobState {
    QUEUED,
    RUNNING,
    DONE,
    SKIPPED,
    FAILED,
}

public data class Job(
    val id: JobId,
    val videoId: VideoId,
    val trigger: JobTrigger,
    val state: JobState,
    val queuedAtEpochMs: Long,
    val claimedAtEpochMs: Long?,
    val claimToken: String?,
) {
    init {
        require((state == JobState.RUNNING) == (claimToken != null)) {
            "a job is RUNNING exactly when it holds a claim token"
        }
    }
}

/**
 * The terminal outcome of running one job. Exhaustive by construction: there is no
 * fourth case, and none of the three can be built without its explanation.
 */
public sealed interface JobOutcome {
    public val videoId: VideoId

    public data class Compressed(val result: CompressionResult) : JobOutcome {
        override val videoId: VideoId get() = result.videoId
    }

    public data class Skipped(
        override val videoId: VideoId,
        val reason: SkipReason,
    ) : JobOutcome

    public data class Failed(
        override val videoId: VideoId,
        val reason: FailureReason,
    ) : JobOutcome
}
