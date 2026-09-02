package dev.trim.ports.contract

import dev.trim.ports.Thermal
import dev.trim.ports.ThermalReading
import kotlin.test.assertTrue

/**
 * The thermal port's job is to be readable, repeatedly, without ever handing the runner a
 * number it cannot act on. app-architecture §7 requires a device whose headroom API returns
 * 0 or NaN to be reported as unsupported and fall back to coarse status — so a NaN reaching
 * the runner is a contract violation, not a device quirk to be worked around later.
 */
public abstract class ThermalContract {

    public abstract fun createThermal(): Thermal

    public fun cases(): List<ContractCase> = listOf(
        case("every reading is actionable") {
            val thermal = createThermal()
            repeat(READS) {
                when (val reading = thermal.read()) {
                    is ThermalReading.Headroom -> {
                        assertTrue(
                            !reading.headroomConsumed.isNaN(),
                            "NaN headroom must be reported as CoarseOnly, never as Headroom",
                        )
                        assertTrue(
                            reading.headroomConsumed >= 0.0,
                            "headroom consumed was ${reading.headroomConsumed}",
                        )
                    }
                    // A named coarse status is always actionable.
                    is ThermalReading.CoarseOnly -> Unit
                }
            }
        },
        case("polling for hours does not degrade the port") {
            // The runner polls at most every 10 s all night. An implementation that starts
            // throwing after N reads shows up as a night that stopped for no stated reason.
            val thermal = createThermal()
            repeat(READS * 20) { thermal.read() }
        },
    )

    private companion object {
        const val READS = 10
    }
}
