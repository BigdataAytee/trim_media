plugins {
    id("trim.kmp-library")
}

// The contract suites live in commonMain, not commonTest, because two different modules
// have to run them: core/ports-fake's JVM tests today, and androidApp's instrumented
// tests once the real implementations exist. Test code that only one module can see is
// not a contract — it is a test.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:ports"))
            api(kotlin("test"))
            api(libs.kotlinx.coroutines.test)
        }
    }
}
