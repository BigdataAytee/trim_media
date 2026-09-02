plugins {
    id("trim.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:ports"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":core:ports-fake"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.property)
        }
    }
}
