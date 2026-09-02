package dev.trim.model

/**
 * How much an estimate can be trusted. Rendering copy is the UI's business, but the
 * ladder is the model's: a seed estimate comes from a shipped table, a predicted one from
 * observations of this device, and a probed one from an encode that actually happened.
 */
public enum class EstimateConfidence {
    /** From the shipped per-device seed table; the widest band. */
    SEED,

    /** From the predictor's own observations for this (device, camera, codec, …) bucket. */
    PREDICTED,

    /** From a real probe encode of this file; the narrowest band. */
    PROBED,
}

/**
 * The size a file is estimated to become — never a single number.
 *
 * frontend-architecture §4.2: "Estimates render as 'about X' — there is no single-number
 * estimate type before a probe has run." That rule is enforced here rather than in copy:
 * there is no constructor that takes one number, so a screen cannot render a false
 * precision it was never given.
 */
public data class EstimateRange(
    val lowBytes: Long,
    val highBytes: Long,
    val confidence: EstimateConfidence,
) {
    init {
        require(lowBytes >= 0) { "lowBytes must not be negative" }
        require(highBytes >= lowBytes) {
            "highBytes ($highBytes) must not be below lowBytes ($lowBytes)"
        }
    }

    /** The representative value for sorting and totalling. Never render this alone. */
    public val midpointBytes: Long get() = lowBytes + (highBytes - lowBytes) / 2

    public operator fun plus(other: EstimateRange): EstimateRange = EstimateRange(
        lowBytes = lowBytes + other.lowBytes,
        highBytes = highBytes + other.highBytes,
        // A total is only as trustworthy as its least trustworthy part.
        confidence = minOf(confidence, other.confidence),
    )

    public companion object {
        /** A band of [+/- fraction] around [centreBytes], clamped at zero. */
        public fun around(
            centreBytes: Long,
            fraction: Double,
            confidence: EstimateConfidence,
        ): EstimateRange {
            require(fraction >= 0.0) { "fraction must not be negative" }
            val spread = (centreBytes * fraction).toLong()
            return EstimateRange(
                lowBytes = (centreBytes - spread).coerceAtLeast(0L),
                highBytes = centreBytes + spread,
                confidence = confidence,
            )
        }

        /** The identity for [plus] — used when totalling an empty list. */
        public fun none(confidence: EstimateConfidence = EstimateConfidence.PROBED): EstimateRange =
            EstimateRange(0L, 0L, confidence)
    }
}

/** Sums a list of estimates, keeping the weakest confidence in the set. */
public fun Iterable<EstimateRange>.sumEstimates(): EstimateRange =
    fold(EstimateRange.none()) { acc, next -> acc + next }
