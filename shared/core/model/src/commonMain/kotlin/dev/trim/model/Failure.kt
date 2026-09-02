package dev.trim.model

/**
 * The four kinds of failure from app-architecture §10. Every failure in the system is one
 * of these, and the kind decides who owns it and what the user sees.
 */
public enum class FailureKind {
    /** Expected skip — the reason goes in the "can't be shrunk" list. */
    EXPECTED_SKIP,

    /** Retryable interruption — the job resumes; the notification notes the pause. */
    RETRYABLE_INTERRUPTION,

    /** File-level failure — the original is untouched; a row appears in History's skipped list. */
    FILE_LEVEL,

    /** Invariant breach — nothing is lost, and diagnostics record it. */
    INVARIANT_BREACH,
}

/**
 * A file-level or invariant-level failure. Expected skips are [SkipReason]s and never
 * appear here; retryable interruptions are [PauseReason]s and never appear here either.
 * Keeping the three sets disjoint is what makes the runner's `when` blocks readable.
 */
public sealed interface FailureReason : RejectionReason {

    public val kind: FailureKind

    /** The encode finished but did not meet the verifier's bar. */
    public data class VerificationFailed(val detail: VerificationFailure) : FailureReason {
        override val kind: FailureKind = FailureKind.FILE_LEVEL
        override val displayText: String = detail.displayText
    }

    /** The source file changed underneath the pipeline; everything measured is stale. */
    public data class SourceChanged(
        val expected: SourceFingerprint,
        val actual: SourceFingerprint?,
    ) : FailureReason {
        override val kind: FailureKind = FailureKind.FILE_LEVEL
        override val displayText: String = "the file changed while it was being shrunk"
    }

    /** The hardware encoder failed in a way that is not a reclaim and not retryable. */
    public data class EncoderError(val detail: String) : FailureReason {
        override val kind: FailureKind = FailureKind.FILE_LEVEL
        override val displayText: String = "the video encoder couldn't handle this file"
    }

    /** Storage refused a read or a write. */
    public data class StorageError(val detail: String) : FailureReason {
        override val kind: FailureKind = FailureKind.FILE_LEVEL
        override val displayText: String = "couldn't read or write the file"
    }

    /** Out of scratch space for the temporary encode. */
    public data class OutOfSpace(val neededBytes: Long, val availableBytes: Long) : FailureReason {
        override val kind: FailureKind = FailureKind.FILE_LEVEL
        override val displayText: String = "not enough free space to work with"
    }

    /** The user or the scheduler cancelled the job. */
    public data object Cancelled : FailureReason {
        override val kind: FailureKind = FailureKind.FILE_LEVEL
        override val displayText: String = "cancelled"
    }

    /**
     * The replace sequence failed part-way and was rolled back. The original is intact —
     * that is the invariant — but the event is recorded loudly.
     */
    public data class ReplaceRolledBack(
        val failedStep: Int,
        val failedStepName: String,
        val detail: String,
    ) : FailureReason {
        override val kind: FailureKind = FailureKind.INVARIANT_BREACH
        override val displayText: String = "couldn't finish safely, so nothing was changed"
    }
}

/** Why the verifier rejected an encode. Each one is a separate, checkable condition. */
public sealed interface VerificationFailure {
    public val displayText: String

    public data class NotSmaller(val originalBytes: Long, val encodedBytes: Long) :
        VerificationFailure {
        override val displayText: String = "the smaller version wasn't actually smaller"
    }

    public data class DurationChanged(val expectedMs: Long, val actualMs: Long) :
        VerificationFailure {
        override val displayText: String = "the shrunk copy didn't match the original's length"
    }

    public data class ScoreBelowTarget(val score: QualityScore, val target: QualityScore) :
        VerificationFailure {
        override val displayText: String = "the result didn't look good enough"
    }

    public data class ColorRangeChanged(val expected: ColorRange, val actual: ColorRange) :
        VerificationFailure {
        override val displayText: String = "the colours didn't survive the encode"
    }
}
