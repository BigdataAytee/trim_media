package dev.trim.model

/** What the encoder produces. Hardware-only; there is no software fallback to name. */
public enum class OutputCodec {
    HEVC,
    AV1,
}

/**
 * One point in the search space.
 *
 * [quality] is a CRF-like index where **higher is more aggressive** (smaller file, lower
 * score). It is deliberately not a bitrate: app-architecture §3 searches CRF, and whether
 * a given device exposes constant-quality mode at all is an open risk that Milestone 3
 * resolves (DECISIONS D2.4). The search operates on this index; mapping it to a device's
 * actual rate-control mode is the Codec implementation's problem, not the pipeline's.
 */
public data class EncodeSetting(
    val quality: Int,
    val outputCodec: OutputCodec,
) : Comparable<EncodeSetting> {
    init {
        require(quality in MIN_QUALITY..MAX_QUALITY) {
            "quality $quality out of range $MIN_QUALITY..$MAX_QUALITY"
        }
    }

    /** Ordered by aggression: a larger index is a more aggressive setting. */
    override fun compareTo(other: EncodeSetting): Int {
        require(outputCodec == other.outputCodec) {
            "settings for different output codecs are not on one scale"
        }
        return quality.compareTo(other.quality)
    }

    public companion object {
        public const val MIN_QUALITY: Int = 0
        public const val MAX_QUALITY: Int = 51
    }
}

/**
 * The closed range of settings the search may consider, chosen from the source codec
 * (app-architecture §3: "the bracket depends on source codec (HEVC may need CRF < 18)").
 *
 * [safest] is the least aggressive end — the one the Prober tries first for its early
 * abort. [mostAggressive] is the other end.
 */
public data class Bracket(
    val safest: EncodeSetting,
    val mostAggressive: EncodeSetting,
) {
    init {
        require(safest.outputCodec == mostAggressive.outputCodec) {
            "a bracket cannot straddle two output codecs"
        }
        require(safest.quality <= mostAggressive.quality) {
            "safest (${safest.quality}) must not be more aggressive than " +
                "mostAggressive (${mostAggressive.quality})"
        }
    }

    public val outputCodec: OutputCodec get() = safest.outputCodec

    public val size: Int get() = mostAggressive.quality - safest.quality + 1

    public fun contains(setting: EncodeSetting): Boolean =
        setting.outputCodec == outputCodec &&
            setting.quality in safest.quality..mostAggressive.quality

    public fun settingAt(quality: Int): EncodeSetting {
        require(quality in safest.quality..mostAggressive.quality) {
            "quality $quality outside bracket ${safest.quality}..${mostAggressive.quality}"
        }
        return EncodeSetting(quality, outputCodec)
    }

    /** Every setting from safest to most aggressive, in that order. */
    public fun settings(): List<EncodeSetting> =
        (safest.quality..mostAggressive.quality).map { EncodeSetting(it, outputCodec) }

    /** The sub-bracket strictly below [ceiling], or null when [ceiling] is already the safest. */
    public fun below(ceiling: EncodeSetting): Bracket? {
        if (ceiling.quality <= safest.quality) return null
        return Bracket(safest, settingAt(ceiling.quality - 1))
    }
}
