package dev.trim.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.trim.data.db.TrimDatabase

/**
 * The JVM driver, used by tests and by the desktop tooling. [path] defaults to an
 * in-memory database, which is what every test wants.
 */
public class JvmDriverFactory(
    private val path: String = JdbcSqliteDriver.IN_MEMORY,
) : DriverFactory {
    override fun create(): SqlDriver = JdbcSqliteDriver(path).also { driver ->
        TrimDatabase.Schema.create(driver)
        // Foreign keys are off by default in SQLite, which would quietly disable the
        // ON DELETE CASCADE that keeps candidates from outliving their videos.
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    }
}
