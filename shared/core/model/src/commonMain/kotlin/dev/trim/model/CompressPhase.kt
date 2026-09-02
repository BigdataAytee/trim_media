package dev.trim.model

/**
 * Named progress. There is no anonymous progress state and no unnamed spinner
 * (frontend-architecture §4.2/§5): the phase list renders as a checklist, so every phase
 * has to have a name in the type system before it can have one on screen.
 */
public sealed interface CompressPhase {

    public val videoId: VideoId

    /** Noise and headroom checks — before any encode has happened. */
    public data class Checking(override val videoId: VideoId) : CompressPhase

    /** Probing and searching for the setting; [probesDone] is what the checklist counts. */
    public data class FindingSetting(
        override val videoId: VideoId,
        val probesDone: Int,
    ) : CompressPhase {
        init {
            require(probesDone >= 0) { "probesDone must not be negative" }
        }
    }

    /** The full-file encode. */
    public data class Encoding(
        override val videoId: VideoId,
        val fractionComplete: Double,
        val etaSeconds: Long?,
    ) : CompressPhase {
        init {
            require(fractionComplete in 0.0..1.0) {
                "fractionComplete $fractionComplete out of range 0..1"
            }
            require(etaSeconds == null || etaSeconds >= 0) { "etaSeconds must not be negative" }
        }
    }

    /** Verifying the encode against the source. */
    public data class Verifying(override val videoId: VideoId) : CompressPhase

    /**
     * Paused, with the reason named — a thermal duty cycle or a codec the OS reclaimed.
     * A pause is not a failure and not a silence; the notification says which it is
     * (app-architecture §7/§10).
     */
    public data class Paused(
        override val videoId: VideoId,
        val reason: PauseReason,
    ) : CompressPhase

    /** Finished, with the outcome. */
    public data class Done(
        override val videoId: VideoId,
        val result: CompressionResult,
    ) : CompressPhase

    /**
     * Not compressed, with the reason — an expected skip or a file-level failure. Both
     * land in History's skipped list (app-architecture §10), and both carry plain-language
     * text, which is the only thing [RejectionReason] promises.
     */
    public data class Rejected(
        override val videoId: VideoId,
        val reason: RejectionReason,
    ) : CompressPhase
}

/** Why the runner is not currently making progress. Every pause names itself. */
public sealed interface PauseReason {
    public val displayText: String

    public data class Thermal(val headroomConsumed: Double) : PauseReason {
        override val displayText: String = "paused to let your phone cool down"
    }

    public data object CodecReclaimed : PauseReason {
        override val displayText: String = "paused — another app needed the video encoder"
    }

    public data object WaitingForCharger : PauseReason {
        override val displayText: String = "waiting until you're charging"
    }

    public data object WaitingForIdle : PauseReason {
        override val displayText: String = "waiting until you're not using your phone"
    }
}

/** The successful outcome of one file. */
public data class CompressionResult(
    val videoId: VideoId,
    val originalBytes: Long,
    val compressedBytes: Long,
    val setting: EncodeSetting,
    val verifiedScore: QualityScore,
    val originalFate: OriginalFate,
) {
    init {
        require(originalBytes > 0) { "originalBytes must be positive" }
        require(compressedBytes > 0) { "compressedBytes must be positive" }
        require(compressedBytes < originalBytes) {
            "a result that is not smaller is not a result: " +
                "$compressedBytes >= $originalBytes"
        }
    }

    public val savedBytes: Long get() = originalBytes - compressedBytes

    public val savedFraction: Double get() = savedBytes.toDouble() / originalBytes.toDouble()
}
