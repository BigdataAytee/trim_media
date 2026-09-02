package dev.trim.ports.fake

import dev.trim.ports.Thermal
import dev.trim.ports.ThermalReading
import dev.trim.ports.ThermalStatus

/**
 * Thermal readings from a script. The default is a cold device; the interesting cases are
 * [oscillating] (the signal that would cause stutter-stepping without hysteresis) and
 * [unsupportedApi] (the device whose headroom API returns zero forever, §7).
 */
public class FakeThermal(
    private var readings: List<ThermalReading> = listOf(ThermalReading.Headroom(0.1)),
) : Thermal {

    /** Every read served, in order. */
    public val served: MutableList<ThermalReading> = mutableListOf()

    private var index = 0

    override suspend fun read(): ThermalReading {
        val reading = readings[minOf(index, readings.lastIndex)]
        index++
        served += reading
        return reading
    }

    public fun script(vararg readings: ThermalReading) {
        this.readings = readings.toList()
        index = 0
    }

    public fun scriptHeadroom(vararg headroom: Double) {
        script(*headroom.map { ThermalReading.Headroom(it) }.toTypedArray())
    }

    public companion object {
        /** Cool device, never pauses. */
        public fun cool(): FakeThermal = FakeThermal(listOf(ThermalReading.Headroom(0.1)))

        /**
         * A signal that crosses the pause threshold and falls back below it every other
         * poll. Without the minimum-pause rule (DECISIONS D6.2) this makes the runner
         * stutter-step; with it, it does not.
         */
        public fun oscillating(cycles: Int = 8): FakeThermal = FakeThermal(
            List(cycles) { i ->
                ThermalReading.Headroom(if (i % 2 == 0) 0.85 else 0.35)
            } + ThermalReading.Headroom(0.2),
        )

        /** Heats up, stays hot for [hotPolls] polls, then cools for good. */
        public fun storm(hotPolls: Int = 5): FakeThermal = FakeThermal(
            listOf(ThermalReading.Headroom(0.2)) +
                List(hotPolls) { ThermalReading.Headroom(0.9) } +
                List(4) { ThermalReading.Headroom(0.3) },
        )

        /** The device whose forecast API is a lie; §7 says fall back to coarse status. */
        public fun unsupportedApi(status: ThermalStatus = ThermalStatus.LIGHT): FakeThermal =
            FakeThermal(listOf(ThermalReading.CoarseOnly(status)))
    }
}
