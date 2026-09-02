package dev.trim.ports

/**
 * Thermal state, coarse or fine. app-architecture §7: poll at most every 10 s, and at the
 * first call returning 0/NaN mark the API unsupported and fall back to coarse status.
 * That fallback is a *reading*, not an exception, so the runner's `when` stays exhaustive.
 */
public interface Thermal {
    public suspend fun read(): ThermalReading
}

public sealed interface ThermalReading {
    /**
     * [headroomConsumed] is 0.0 (cold) to 1.0 (at the throttling threshold). Values above
     * 1.0 are reported as-is: the device is already throttling.
     */
    public data class Headroom(val headroomConsumed: Double) : ThermalReading {
        init {
            require(!headroomConsumed.isNaN()) { "headroom must not be NaN; report Unsupported" }
            require(headroomConsumed >= 0.0) { "headroom must not be negative" }
        }
    }

    /** The forecast API is unavailable on this device; only a coarse status exists. */
    public data class CoarseOnly(val status: ThermalStatus) : ThermalReading
}

public enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    ;

    /** Anything from MODERATE up means stop asking the encoder for favours. */
    public val shouldPause: Boolean get() = ordinal >= MODERATE.ordinal
}
