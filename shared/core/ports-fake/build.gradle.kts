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
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
