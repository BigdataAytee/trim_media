plugins {
    id("trim.guards")
}

tasks.register("check") {
    group = "verification"
    description = "Runs every module's checks plus the build-enforced invariants of §8."
    // `:core` is a container with no build file and no check task; only real modules count.
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
}
