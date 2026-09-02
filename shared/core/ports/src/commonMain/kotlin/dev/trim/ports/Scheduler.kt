package dev.trim.ports

import dev.trim.model.JobId
import kotlinx.coroutines.flow.Flow

/**
 * The OS work scheduler. The nightly path exists because WorkManager enforces
 * charging + idle at the OS level: unplugging kills the process, so no app bug can drain
 * the battery on that path (app-architecture §7).
 */
public interface Scheduler {

    public suspend fun scheduleNightly(constraints: NightlyConstraints)

    public suspend fun cancelNightly()

    /** Asks the OS to run these jobs now; used by the explicit-tap path. */
    public suspend fun runNow(jobs: List<JobId>)

    /** The constraints as they currently stand, so the runner can pause with a reason. */
    public fun observeConditions(): Flow<DeviceConditions>
}

public data class NightlyConstraints(
    val requiresCharging: Boolean,
    val requiresIdle: Boolean,
    val requiresFullCharge: Boolean,
    val stopBeforeNextAlarm: Boolean,
) {
    public companion object {
        /** The nightly defaults of §7: charging + idle, OS-enforced. */
        public val NIGHTLY: NightlyConstraints = NightlyConstraints(
            requiresCharging = true,
            requiresIdle = true,
            requiresFullCharge = false,
            stopBeforeNextAlarm = true,
        )
    }
}

public data class DeviceConditions(
    val isCharging: Boolean,
    val batteryFraction: Double,
    val isDeviceIdle: Boolean,
    val millisUntilNextAlarm: Long?,
) {
    init {
        require(batteryFraction in 0.0..1.0) { "batteryFraction out of range 0..1" }
    }
}
