rootProject.name = "trim"

pluginManagement {
    includeBuild("buildLogic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// Milestone 1 — platform-free core only. No androidApp, no feature/*, no native/.
include(":core:model")
include(":core:ports")
include(":core:ports-fake")
include(":core:pipeline")
include(":core:data")
include(":core:domain")

project(":core:model").projectDir = file("shared/core/model")
project(":core:ports").projectDir = file("shared/core/ports")
project(":core:ports-fake").projectDir = file("shared/core/ports-fake")
project(":core:pipeline").projectDir = file("shared/core/pipeline")
project(":core:data").projectDir = file("shared/core/data")
project(":core:domain").projectDir = file("shared/core/domain")
