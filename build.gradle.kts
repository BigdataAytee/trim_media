plugins {
    id("trim.guards")
}

tasks.register("check") {
    group = "verification"
    description = "Runs every module's checks plus the build-enforced invariants of §8."
    dependsOn(subprojects.map { "${it.path}:check" })
}
