package dev.trim.model

/**
 * Why a file will not be compressed. There is no bare "skipped" — every rejection carries
 * one of these, in plain words, because the "can't be shrunk" list is the app's
 * credibility (frontend-architecture §5).
 *
 * The five reasons of frontend-architecture §4.2 plus the two that app-architecture §3
 * requires the HeadroomCheck and Prober stages to be able to emit. See DECISIONS D2.1.
 */
public sealed interface SkipReason {

    /** One sentence, sentence case, no codes. Rendered verbatim by SkippedRow. */
    public val displayText: String

    /** Already close enough to the efficiency frontier that shrinking it would cost quality. */
    public data class AlreadyEfficient(
        val codec: VideoCodec,
        val bitsPerPixelPerSecond: Double,
    ) : SkipReason {
        override val displayText: String = "already efficiently encoded"
    }

    /** Grain and sensor noise dominate; every bit saved is visible (app-architecture §3). */
    public data class TooNoisy(val noiseEnergy: Double) : SkipReason {
        override val displayText: String = "too noisy to shrink"
    }

    /** HDR transfer or >8-bit: re-encoding risks the tone mapping. */
    public data class Hdr(val transfer: TransferFunction, val bitDepth: Int) : SkipReason {
        override val displayText: String = "HDR video is left untouched"
    }

    /** Multiple video/audio tracks, or subtitle/metadata tracks the encoder would drop. */
    public data class SecondaryTrack(
        val videoTracks: Int,
        val audioTracks: Int,
        val otherTracks: Int,
    ) : SkipReason {
        override val displayText: String = "has extra tracks that would be lost"
    }

    /** Below the size where the work is worth doing at all. */
    public data class TooSmall(val sizeBytes: Long, val minimumBytes: Long) : SkipReason {
        override val displayText: String = "too small to be worth shrinking"
    }

    /** The source cannot itself score above the target, so no setting could either. */
    public data class NoHeadroom(
        val ceiling: QualityScore,
        val required: QualityScore,
    ) : SkipReason {
        override val displayText: String = "not enough quality headroom to shrink safely"
    }

    /** Even the safest setting in the bracket missed the target — the Prober's early abort. */
    public data class CannotReachTarget(
        val bestScore: QualityScore,
        val target: QualityScore,
    ) : SkipReason {
        override val displayText: String = "can't be shrunk without visible loss"
    }
}
