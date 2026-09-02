plugins {
    id("trim.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:ports"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            // The fakes are held to the same contract the real implementations will be.
            implementation(project(":core:ports-contract"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
