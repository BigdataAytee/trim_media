package dev.trim.ports.contract

import dev.trim.ports.Clock
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Time may be virtual or real, but it may not run backwards and a sleep may not return
 * early — the thermal duty cycle's minimum pause is only a minimum if the clock honours it.
 */
public abstract class ClockContract {

    public abstract fun createClock(): Clock

    public fun cases(): List<ContractCase> = listOf(
        case("time never goes backwards") {
            val clock = createClock()
            var previous = clock.nowEpochMs()
            repeat(TICKS) {
                clock.sleep(1)
                val now = clock.nowEpochMs()
                assertTrue(now >= previous, "the clock went backwards: $previous then $now")
                previous = now
            }
        },
        case("a sleep advances the clock by at least the requested time") {
            val clock = createClock()
            val before = clock.nowEpochMs()
            clock.sleep(SLEEP_MS)
            val elapsed = clock.nowEpochMs() - before
            assertTrue(
                elapsed >= SLEEP_MS,
                "sleep($SLEEP_MS) returned after only $elapsed ms; a minimum pause that " +
                    "returns early is not a minimum (app-architecture §7)",
            )
        },
        case("a sleep of zero is allowed and a negative sleep is refused") {
            val clock = createClock()
            clock.sleep(0)
            assertFailsWith<IllegalArgumentException> { clock.sleep(-1) }
        },
    )

    private companion object {
        const val TICKS = 5
        const val SLEEP_MS = 50L
    }
}
