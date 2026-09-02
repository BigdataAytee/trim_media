package dev.trim.model

/**
 * The user's quality target, as a VMAF value. The pipeline searches on XPSNR and converts
 * through the calibration table (app-architecture §9); the setting itself stays in the
 * unit the user's mental model uses.
 */
public enum class QualityTarget(public val vmaf: Double) {
    /** Visually lossless for practical purposes. */
    HIGHEST(96.0),

    /** The default. */
    BALANCED(95.0),

    /** Accepts a little more loss for a lot more saving. */
    SMALLEST(93.0),
    ;

    public val score: QualityScore get() = QualityScore(Metric.VMAF, vmaf)
}

/** Everything Settings owns. Each control observes one field (frontend-architecture §5). */
public data class TrimSettings(
    val qualityTarget: QualityTarget,
    val nightlyEnabled: Boolean,
    val requireFullCharge: Boolean,
    val stopBeforeAlarm: Boolean,
    val nightlyByteCap: Long?,
    val workWhileUsingPhone: Boolean,
    val defaultOriginalFate: OriginalFate,
) {
    init {
        require(nightlyByteCap == null || nightlyByteCap > 0) {
            "a nightly byte cap of zero would silently disable the nightly run; " +
                "use nightlyEnabled = false"
        }
    }

    public companion object {
        public val DEFAULT: TrimSettings = TrimSettings(
            qualityTarget = QualityTarget.BALANCED,
            nightlyEnabled = true,
            requireFullCharge = false,
            stopBeforeAlarm = true,
            nightlyByteCap = null,
            workWhileUsingPhone = false,
            // DECISIONS D0.1: spec.md would normally set this; 30 days is the value the
            // frontend document's example copy uses ("Original: kept 30 days").
            defaultOriginalFate = OriginalFate.KeptDays(30),
        )
    }
}
