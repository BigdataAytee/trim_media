plugins {
    id("trim.kmp-library")
    // version comes from buildLogic's classpath (libs.sqldelight.gradle.plugin)
    id("app.cash.sqldelight")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
        commonTest.dependencies {
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
            verifyMigrations.set(true)
        }
    }
}
