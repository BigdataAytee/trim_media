import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    // Milestone 1 targets the JVM only: `./gradlew check` must pass on a machine
    // with no Android SDK. Milestone 2 adds androidTarget() to these same modules.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    jvmToolchain(21)

    sourceSets {
        commonMain {
            dependencies {
                // intentionally empty — modules declare their own
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
