package dev.trim.pipeline.run

import dev.trim.model.JobTrigger
import dev.trim.model.PauseReason
import dev.trim.pipeline.PipelineConfig
import dev.trim.ports.DeviceConditions
import dev.trim.ports.ThermalReading
import dev.trim.ports.ThermalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RunPolicyTest {

    private val policy = RunPolicy()
    private val config = PipelineConfig()

    private val pluggedInAndIdle = DeviceConditions(
        isCharging = true,
        batteryFraction = 0.7,
        isDeviceIdle = true,
        millisUntilNextAlarm = null,
    )
    private val inUseOnBattery = DeviceConditions(
        isCharging = false,
        batteryFraction = 0.5,
        isDeviceIdle = false,
        millisUntilNextAlarm = null,
    )

    private fun state(
        trigger: JobTrigger = JobTrigger.NIGHTLY,
        conditions: DeviceConditions = pluggedInAndIdle,
        thermal: ThermalReading = ThermalReading.Headroom(0.2),
        pausedForHeat: Boolean = false,
        requireFullCharge: Boolean = false,
        stopBeforeAlarm: Boolean = true,
        workWhileUsingPhone: Boolean = false,
        nightlyByteCap: Long? = null,
        bytesProcessedTonight: Long = 0,
        estimatedJobMillis: Long = 60_000,
    ) = RunState(
        trigger, conditions, thermal, pausedForHeat, requireFullCharge, stopBeforeAlarm,
        workWhileUsingPhone, nightlyByteCap, bytesProcessedTonight, estimatedJobMillis,
    )

    @Test
    fun `the nightly path proceeds while charging and idle`() {
        assertEquals(RunDecision.Proceed, policy.decide(state()))
    }

    @Test
    fun `the nightly path stops when the phone is unplugged`() {
        val decision = policy.decide(state(conditions = pluggedInAndIdle.copy(isCharging = false)))
        assertIs<RunDecision.Stop>(decision)
        assertEquals(StopReason.NotCharging, decision.reason)
    }

    @Test
    fun `the nightly path waits rather than stopping when the phone is in use`() {
        val decision = policy.decide(
            state(conditions = pluggedInAndIdle.copy(isDeviceIdle = false)),
        )
        assertIs<RunDecision.Pause>(decision)
        assertEquals(PauseReason.WaitingForIdle, decision.reason)
    }

    @Test
    fun `wait-for-full-charge holds until the battery is nearly full`() {
        val nearlyEmpty = policy.decide(
            state(conditions = pluggedInAndIdle.copy(batteryFraction = 0.4), requireFullCharge = true),
        )
        assertIs<RunDecision.Pause>(nearlyEmpty)
        assertEquals(PauseReason.WaitingForCharger, nearlyEmpty.reason)

        val full = policy.decide(
            state(conditions = pluggedInAndIdle.copy(batteryFraction = 0.99), requireFullCharge = true),
        )
        assertEquals(RunDecision.Proceed, full)
    }

    @Test
    fun `a job that would run past the alarm does not start`() {
        val decision = policy.decide(
            state(
                conditions = pluggedInAndIdle.copy(millisUntilNextAlarm = 30_000),
                estimatedJobMillis = 60_000,
            ),
        )
        assertIs<RunDecision.Stop>(decision)
        assertEquals(StopReason.AlarmTooSoon, decision.reason)
    }

    @Test
    fun `an alarm far enough away is not a reason to stop`() {
        assertEquals(
            RunDecision.Proceed,
            policy.decide(
                state(
                    conditions = pluggedInAndIdle.copy(millisUntilNextAlarm = 3_600_000),
                    estimatedJobMillis = 60_000,
                ),
            ),
        )
    }

    @Test
    fun `the nightly byte cap stops the run for the night`() {
        val decision = policy.decide(
            state(nightlyByteCap = 1_000, bytesProcessedTonight = 1_000),
        )
        assertIs<RunDecision.Stop>(decision)
        assertEquals(StopReason.NightlyCapReached, decision.reason)
    }

    @Test
    fun `a user-initiated run is allowed on battery`() {
        assertEquals(
            RunDecision.Proceed,
            policy.decide(
                state(
                    trigger = JobTrigger.USER_INITIATED,
                    conditions = inUseOnBattery,
                    workWhileUsingPhone = true,
                ),
            ),
        )
    }

    @Test
    fun `a user-initiated run waits while the phone is in use unless opted in`() {
        val decision = policy.decide(
            state(trigger = JobTrigger.USER_INITIATED, conditions = inUseOnBattery),
        )
        assertIs<RunDecision.Pause>(decision)
        assertEquals(PauseReason.WaitingForIdle, decision.reason)
    }

    // ---- heat outranks everything ----

    @Test
    fun `heat pauses a user-initiated run just as it pauses the nightly one`() {
        for (trigger in JobTrigger.entries) {
            val decision = policy.decide(
                state(
                    trigger = trigger,
                    conditions = pluggedInAndIdle,
                    thermal = ThermalReading.Headroom(0.85),
                ),
            )
            assertIs<RunDecision.Pause>(decision, "trigger $trigger ignored the thermal budget")
            assertIs<PauseReason.Thermal>(decision.reason)
        }
    }

    @Test
    fun `hysteresis means the resume threshold is lower than the pause threshold`() {
        // 0.6 is between resume (0.5) and pause (0.7). A cool run keeps going; a run that
        // is already paused stays paused. Without the gap, this value alternates.
        assertEquals(
            RunDecision.Proceed,
            policy.decide(state(thermal = ThermalReading.Headroom(0.6), pausedForHeat = false)),
        )
        assertIs<RunDecision.Pause>(
            policy.decide(state(thermal = ThermalReading.Headroom(0.6), pausedForHeat = true)),
        )
    }

    @Test
    fun `an oscillating thermal signal cannot stutter-step the encoder`() {
        // The signal crosses the pause threshold and falls back every other poll. Every
        // pause it produces carries the minimum duration, so the encoder is never asked to
        // restart faster than that.
        val oscillation = listOf(0.85, 0.35, 0.85, 0.35, 0.9, 0.3)
        var paused = false
        var pauses = 0
        for (headroom in oscillation) {
            val decision = policy.decide(
                state(thermal = ThermalReading.Headroom(headroom), pausedForHeat = paused),
            )
            when (decision) {
                is RunDecision.Pause -> {
                    pauses++
                    paused = true
                    assertEquals(config.minimumThermalPauseMs, decision.minimumMillis)
                }
                RunDecision.Proceed -> paused = false
                is RunDecision.Stop -> error("heat is never a reason to stop for the night")
            }
        }
        assertEquals(3, pauses)
    }

    @Test
    fun `a device with no headroom API still pauses on coarse thermal status`() {
        assertEquals(
            RunDecision.Proceed,
            policy.decide(state(thermal = ThermalReading.CoarseOnly(ThermalStatus.LIGHT))),
        )
        val decision = policy.decide(
            state(thermal = ThermalReading.CoarseOnly(ThermalStatus.SEVERE)),
        )
        assertIs<RunDecision.Pause>(decision)
        assertIs<PauseReason.Thermal>(decision.reason)
    }
}
