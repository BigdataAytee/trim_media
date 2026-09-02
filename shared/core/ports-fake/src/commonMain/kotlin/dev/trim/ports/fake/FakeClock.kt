package dev.trim.ports.fake

import dev.trim.ports.Clock

/**
 * Virtual time. [sleep] advances the clock without waiting, so a test can run a
 * thirty-day retention window in a microsecond and still assert on the timestamps.
 */
public class FakeClock(startEpochMs: Long = 1_700_000_000_000) : Clock {

    public var nowMs: Long = startEpochMs
        private set

    /** Every sleep this clock has served, in order — used to assert on pause durations. */
    public val sleeps: MutableList<Long> = mutableListOf()

    override fun nowEpochMs(): Long = nowMs

    override suspend fun sleep(millis: Long) {
        require(millis >= 0) { "cannot sleep for negative time" }
        sleeps += millis
        nowMs += millis
    }

    public fun advance(millis: Long) {
        nowMs += millis
    }
}
