package dev.trim.ports.fake

import dev.trim.model.JobId
import dev.trim.ports.DeviceConditions
import dev.trim.ports.NightlyConstraints
import dev.trim.ports.Scheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

public class FakeScheduler(
    initialConditions: DeviceConditions = PLUGGED_IN_AND_IDLE,
) : Scheduler {

    public val conditions: MutableStateFlow<DeviceConditions> = MutableStateFlow(initialConditions)

    public var nightlyConstraints: NightlyConstraints? = null
        private set

    public val runNowCalls: MutableList<List<JobId>> = mutableListOf()

    override suspend fun scheduleNightly(constraints: NightlyConstraints) {
        nightlyConstraints = constraints
    }

    override suspend fun cancelNightly() {
        nightlyConstraints = null
    }

    override suspend fun isNightlyScheduled(): Boolean = nightlyConstraints != null

    override suspend fun runNow(jobs: List<JobId>) {
        runNowCalls += jobs
    }

    override fun observeConditions(): Flow<DeviceConditions> = conditions

    public companion object {
        public val PLUGGED_IN_AND_IDLE: DeviceConditions = DeviceConditions(
            isCharging = true,
            batteryFraction = 0.8,
            isDeviceIdle = true,
            millisUntilNextAlarm = null,
        )

        public val ON_BATTERY_IN_USE: DeviceConditions = DeviceConditions(
            isCharging = false,
            batteryFraction = 0.45,
            isDeviceIdle = false,
            millisUntilNextAlarm = null,
        )
    }
}
