package dev.trim.model

/** The two metrics the scorer speaks. They are not interchangeable, ever. */
public enum class Metric {
    /** Fast, used to drive the search. */
    XPSNR,

    /** Slow, used to verify and to calibrate XPSNR thresholds. */
    VMAF,
}

/**
 * A quality score, tagged with the metric that produced it.
 *
 * The tag is the point: an XPSNR value and a VMAF value live on different scales, and the
 * XPSNR↔VMAF-95 relationship is a per-device calibration table (app-architecture §9).
 * Comparing across metrics is a bug, so [compareTo] refuses to do it.
 */
public data class QualityScore(
    val metric: Metric,
    val value: Double,
) : Comparable<QualityScore> {
    init {
        require(!value.isNaN()) { "quality score must not be NaN" }
        require(value in 0.0..100.0) { "quality score $value out of range 0..100" }
    }

    override fun compareTo(other: QualityScore): Int {
        require(metric == other.metric) {
            "cannot compare $metric to ${other.metric}: they are different scales " +
                "(app-architecture §9)"
        }
        return value.compareTo(other.value)
    }

    public operator fun plus(delta: Double): QualityScore =
        QualityScore(metric, (value + delta).coerceIn(0.0, 100.0))

    public operator fun minus(delta: Double): QualityScore = plus(-delta)
}

public fun xpsnr(value: Double): QualityScore = QualityScore(Metric.XPSNR, value)

public fun vmaf(value: Double): QualityScore = QualityScore(Metric.VMAF, value)
