plugins {
    id("trim.kmp-library")
    // version comes from buildLogic's classpath (libs.sqldelight.gradle.plugin)
    id("app.cash.sqldelight")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            // Dependency inversion, on purpose: core/pipeline owns the persistence
            // abstractions it needs (ProcessedLedger, UndoJournal) and core/data supplies
            // them. Nothing in core/pipeline imports core/data (DECISIONS D6.8).
            api(project(":core:pipeline"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
        commonTest.dependencies {
            implementation(project(":core:ports-fake"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
    }
}

sqldelight {
    databases {
        create("TrimDatabase") {
            packageName.set("dev.trim.data.db")
            srcDirs.setFrom("src/commonMain/sqldelight")
            // Off until the first migration exists. SQLDelight only registers the
            // schema-snapshot task once there is a .sqm file to derive from, so switching
            // this on now fails the build asking for a snapshot it will not generate.
            // Turn it on in the same commit as migration 1.sqm (DECISIONS D6.9).
            verifyMigrations.set(false)
        }
    }
}
