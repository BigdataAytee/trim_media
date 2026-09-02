package dev.trim.ports.contract

import dev.trim.model.JobId
import dev.trim.ports.NightlyConstraints
import dev.trim.ports.Scheduler
import kotlinx.coroutines.flow.first
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The nightly path's safety property is structural rather than behavioural: WorkManager
 * enforces charging and idle at the OS level, so unplugging kills the process and no app
 * bug can drain the battery (app-architecture §7). What the *port* has to promise is that
 * the constraints it was asked for are the constraints it registered, and that scheduling
 * is idempotent — a user who opens Settings five times must not get five nightly runs.
 */
public abstract class SchedulerContract {

    public abstract fun createScheduler(): Scheduler

    public fun cases(): List<ContractCase> = listOf(
        case("nothing is scheduled until something schedules it") {
            assertFalse(createScheduler().isNightlyScheduled())
        },
        case("scheduling nightly work is observable") {
            val scheduler = createScheduler()
            scheduler.scheduleNightly(NightlyConstraints.NIGHTLY)
            assertTrue(
                scheduler.isNightlyScheduled(),
                "the scheduler accepted nightly work and then reported none",
            )
        },
        case("scheduling twice does not queue two nightly runs") {
            val scheduler = createScheduler()
            scheduler.scheduleNightly(NightlyConstraints.NIGHTLY)
            scheduler.scheduleNightly(NightlyConstraints.NIGHTLY.copy(requiresFullCharge = true))
            assertTrue(scheduler.isNightlyScheduled())
            // Re-scheduling replaces; the second call's constraints are the ones in force.
            scheduler.cancelNightly()
            assertFalse(
                scheduler.isNightlyScheduled(),
                "one cancel did not undo two schedules, so the second schedule queued a " +
                    "second nightly run",
            )
        },
        case("cancelling is idempotent and safe when nothing is scheduled") {
            val scheduler = createScheduler()
            scheduler.cancelNightly()
            scheduler.cancelNightly()
            assertFalse(scheduler.isNightlyScheduled())
        },
        case("device conditions are always readable and in range") {
            val conditions = createScheduler().observeConditions().first()
            assertTrue(
                conditions.batteryFraction in 0.0..1.0,
                "battery fraction ${conditions.batteryFraction} is outside 0..1",
            )
            conditions.millisUntilNextAlarm?.let { untilAlarm ->
                assertTrue(untilAlarm >= 0, "the next alarm cannot be in the past")
            }
        },
        case("running jobs now is accepted, including an empty list") {
            val scheduler = createScheduler()
            scheduler.runNow(emptyList())
            scheduler.runNow(listOf(JobId("job-1"), JobId("job-2")))
        },
        case("the nightly constraints of §7 are charging and idle") {
            // Not a property of the implementation but of the value every caller passes;
            // it lives here because a port that quietly dropped a constraint would pass
            // every other clause.
            assertEquals(true, NightlyConstraints.NIGHTLY.requiresCharging)
            assertEquals(true, NightlyConstraints.NIGHTLY.requiresIdle)
        },
    )
}
