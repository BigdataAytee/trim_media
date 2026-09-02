package dev.trim.pipeline.calibrate

import dev.trim.model.Metric
import dev.trim.model.QualityScore

/**
 * The XPSNR↔VMAF relationship, which app-architecture §9 insists is **data, not code**:
 * shipped per device class, updatable without touching the native layer, and "flagged
 * loudly in diagnostics when a device falls back to the generic curve".
 *
 * Milestone 4's calibration harness produces the real tables. Milestone 1 ships only the
 * generic curve, so every lookup here returns [CalibrationLookup.GenericCurve] — which is
 * exactly the loud diagnostic §9 asks for, rather than a silently plausible number.
 */
public class CalibrationTable(
    private val perDeviceClass: Map<String, DeviceCurve> = emptyMap(),
    private val generic: DeviceCurve = GENERIC_CURVE,
) {
    public fun xpsnrThresholdFor(
        targetVmaf: QualityScore,
        deviceClass: String?,
    ): CalibrationLookup {
        require(targetVmaf.metric == Metric.VMAF) { "the target is stated in VMAF" }
        val curve = deviceClass?.let { perDeviceClass[it] }
        return if (curve != null) {
            CalibrationLookup.Calibrated(curve.xpsnrFor(targetVmaf.value), deviceClass)
        } else {
            CalibrationLookup.GenericCurve(
                threshold = generic.xpsnrFor(targetVmaf.value),
                diagnostic = "no XPSNR/VMAF calibration for device class " +
                    "'${deviceClass ?: "unknown"}'; using the generic curve. Search decisions " +
                    "on this device are less precise than on a calibrated one (§9).",
            )
        }
    }

    public companion object {
        /**
         * A straight line through the two points the corpus is expected to pin down first:
         * VMAF 93 ≈ XPSNR 38.5, VMAF 96 ≈ XPSNR 42.0. Deliberately crude — a device that
         * relies on it says so in diagnostics.
         */
        public val GENERIC_CURVE: DeviceCurve = DeviceCurve.throughPoints(
            lowVmaf = 93.0,
            lowXpsnr = 38.5,
            highVmaf = 96.0,
            highXpsnr = 42.0,
        )
    }
}

/** A monotone mapping from a VMAF target to the XPSNR value that stands in for it. */
public class DeviceCurve private constructor(
    private val slope: Double,
    private val intercept: Double,
) {
    public fun xpsnrFor(vmaf: Double): QualityScore =
        QualityScore(Metric.XPSNR, (slope * vmaf + intercept).coerceIn(0.0, 100.0))

    public companion object {
        public fun throughPoints(
            lowVmaf: Double,
            lowXpsnr: Double,
            highVmaf: Double,
            highXpsnr: Double,
        ): DeviceCurve {
            require(highVmaf > lowVmaf) { "the two calibration points must differ" }
            val slope = (highXpsnr - lowXpsnr) / (highVmaf - lowVmaf)
            return DeviceCurve(slope, lowXpsnr - slope * lowVmaf)
        }
    }
}

public sealed interface CalibrationLookup {
    public val threshold: QualityScore

    public data class Calibrated(
        override val threshold: QualityScore,
        val deviceClass: String,
    ) : CalibrationLookup

    /** The fallback. [diagnostic] goes into the opt-in diagnostics export, never silently. */
    public data class GenericCurve(
        override val threshold: QualityScore,
        val diagnostic: String,
    ) : CalibrationLookup
}
