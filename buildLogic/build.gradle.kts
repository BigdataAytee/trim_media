plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.sqldelight.gradle.plugin)
}

kotlin {
    jvmToolchain(21)
}
