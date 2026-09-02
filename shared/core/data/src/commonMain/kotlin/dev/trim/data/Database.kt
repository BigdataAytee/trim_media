package dev.trim.data

import app.cash.sqldelight.db.SqlDriver
import dev.trim.data.db.TrimDatabase

/**
 * Creates the platform's driver. JVM in Milestone 1; the Android driver arrives with the
 * Android target in Milestone 2 and the schema does not change (DECISIONS D6.6).
 */
public interface DriverFactory {
    public fun create(): SqlDriver
}

public fun trimDatabase(factory: DriverFactory): TrimDatabase =
    TrimDatabase(factory.create())
