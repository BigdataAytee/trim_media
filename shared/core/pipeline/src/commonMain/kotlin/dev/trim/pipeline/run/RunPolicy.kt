package dev.trim.pipeline.run

import dev.trim.model.JobTrigger
import dev.trim.model.PauseReason
import dev.trim.pipeline.PipelineConfig
import dev.trim.ports.DeviceConditions
import dev.trim.ports.ThermalReading

/**
 * "Should the runner be working right now, and if not, why not?" — a pure function of the
 * device's state, with no ports and no I/O, so every row of app-architecture §7 is a unit
 * test rather than a night spent watching a phone.
 *
 * Two entry points, one policy (§7): the nightly path runs only while the OS-enforced
 * charging + idle constraints hold, and the user-initiated path is allowed on battery
 * because the user asked for it. Both obey thermals; nothing overrides heat.
 *
 * Thermal handling is **hysteretic**: pause above 0.7 headroom-consumed, resume below 0.5.
 * The gap is not a rounding allowance — it is what stops an oscillating signal from
 * stutter-stepping the encoder, and it is paired with a minimum pause so that a signal
 * flapping either side of the threshold cannot produce a pause that ends immediately.
 */
public class RunPolicy(
    private val config: PipelineConfig = PipelineConfig(),
) {

    public fun decide(state: RunState): RunDecision {
        // Heat first: no trigger, and no user, outranks the thermal budget.
        when (val thermal = state.thermal) {
            is ThermalReading.Headroom -> {
                val tooHot = if (state.pausedForHeat) {
                    thermal.headroomConsumed >= config.thermalResumeBelow
                } else {
                    thermal.headroomConsumed >= config.thermalPauseAbove
                }
                if (tooHot) {
                    return RunDecision.Pause(
                        reason = PauseReason.Thermal(thermal.headroomConsumed),
                        minimumMillis = config.minimumThermalPauseMs,
                    )
                }
            }
            is ThermalReading.CoarseOnly ->
                if (thermal.status.shouldPause) {
                    return RunDecision.Pause(
                        reason = PauseReason.Thermal(COARSE_HEADROOM_STANDIN),
                        minimumMillis = config.minimumThermalPauseMs,
                    )
                }
        }

        return when (state.trigger) {
            JobTrigger.NIGHTLY -> decideNightly(state)
            JobTrigger.USER_INITIATED, JobTrigger.SHARE -> decideUserInitiated(state)
        }
    }

    private fun decideNightly(state: RunState): RunDecision {
        val conditions = state.conditions
        if (!conditions.isCharging) {
            // In production WorkManager has already killed the process by now; this branch
            // exists so the policy is honest on its own terms rather than relying on that.
            return RunDecision.Stop(StopReason.NotCharging)
        }
        if (state.requireFullCharge && conditions.batteryFraction < FULL_CHARGE_FRACTION) {
            return RunDecision.Pause(PauseReason.WaitingForCharger, config.minimumThermalPauseMs)
        }
        if (!conditions.isDeviceIdle) {
            return RunDecision.Pause(PauseReason.WaitingForIdle, config.minimumThermalPauseMs)
        }
        if (state.stopBeforeAlarm) {
            val untilAlarm = conditions.millisUntilNextAlarm
            if (untilAlarm != null && untilAlarm <= state.estimatedJobMillis) {
                return RunDecision.Stop(StopReason.AlarmTooSoon)
            }
        }
        val cap = state.nightlyByteCap
        if (cap != null && state.bytesProcessedTonight >= cap) {
            return RunDecision.Stop(StopReason.NightlyCapReached)
        }
        return RunDecision.Proceed
    }

    private fun decideUserInitiated(state: RunState): RunDecision {
        // The user asked. Battery level is their business, not the app's — the only thing
        // that stops a user-initiated run is heat, which is handled above, or an explicit
        // cancellation, which is not a policy question.
        if (!state.conditions.isCharging && !state.conditions.isDeviceIdle &&
            !state.workWhileUsingPhone
        ) {
            return RunDecision.Pause(PauseReason.WaitingForIdle, config.minimumThermalPauseMs)
        }
        return RunDecision.Proceed
    }

    private companion object {
        const val FULL_CHARGE_FRACTION = 0.95

        /**
         * A device with no headroom API that reports MODERATE or worse is treated as being
         * at the pause threshold. The number is never shown to a user — [PauseReason.Thermal]
         * renders as "paused to let your phone cool down" — it exists so the pause carries
         * a single shape regardless of which API produced it.
         */
        const val COARSE_HEADROOM_STANDIN = 1.0
    }
}

/** Everything the policy is allowed to look at. Nothing here is a port. */
public data class RunState(
    val trigger: JobTrigger,
    val conditions: DeviceConditions,
    val thermal: ThermalReading,
    val pausedForHeat: Boolean = false,
    val requireFullCharge: Boolean = false,
    val stopBeforeAlarm: Boolean = true,
    val workWhileUsingPhone: Boolean = false,
    val nightlyByteCap: Long? = null,
    val bytesProcessedTonight: Long = 0,
    val estimatedJobMillis: Long = 0,
)

public sealed interface RunDecision {
    public data object Proceed : RunDecision

    /** Come back later. [minimumMillis] is the floor that prevents stutter-stepping. */
    public data class Pause(
        val reason: PauseReason,
        val minimumMillis: Long,
    ) : RunDecision

    /** Do not come back tonight. */
    public data class Stop(val reason: StopReason) : RunDecision
}

public enum class StopReason(public val displayText: String) {
    NotCharging("stopped because the phone was unplugged"),
    AlarmTooSoon("stopped so it would be finished before your alarm"),
    NightlyCapReached("stopped after tonight's limit"),
}
