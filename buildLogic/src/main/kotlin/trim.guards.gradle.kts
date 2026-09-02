import trim.guards.PendingGuardTask
import trim.guards.StorageWriteGuardTask

// The three build-enforced invariants of app-architecture §8. Guard #3 is live in
// Milestone 1; #1 and #2 police Android code that does not exist yet, so they are
// registered as loudly-failing stubs.

val repoRoot = rootProject.layout.projectDirectory

val guardStorageWrites = tasks.register<StorageWriteGuardTask>("guardStorageWrites") {
    group = "verification"
    description = "Guard #3: user storage is written only by the Replacer."

    annotationName.set("StorageWrite")

    portSources.from(
        repoRoot.dir("shared/core/ports/src").asFileTree.matching { include("**/*.kt") }
    )
    scannedSources.from(
        repoRoot.dir("shared").asFileTree.matching {
            include("**/src/*Main/kotlin/**/*.kt")
        }
    )
    allowedPathFragments.set(
        listOf(
            // The port declaration itself.
            "shared/core/ports/",
            // The one class permitted to write (app-architecture §6).
            "core/pipeline/src/commonMain/kotlin/dev/trim/pipeline/replace/",
        )
    )
    report.set(layout.buildDirectory.file("reports/guards/storage-writes.txt"))
}

tasks.register<PendingGuardTask>("guardNoNetwork") {
    group = "verification"
    description = "Guard #1 (stub): no INTERNET permission, no networking API. TODO(M2)."
    guardName.set("no-network")
    milestone.set("M2")
    rationale.set(
        "Guard #1 checks the MERGED manifest of every Android variant and bans networking " +
            "imports in every source set. Milestone 1 has no androidApp and no merged " +
            "manifest to check, so implementing it now would give a guard with nothing to " +
            "scan — which §8 says must fail rather than pass."
    )
}

tasks.register<PendingGuardTask>("guardCodecFactoryOnly") {
    group = "verification"
    description = "Guard #2 (stub): codecs obtained only via CodecFactory. TODO(M3)."
    guardName.set("codec-factory-only")
    milestone.set("M3")
    rationale.set(
        "Guard #2 bans direct MediaCodec instantiation and software-encoder imports outside " +
            "CodecFactory. Milestone 1 has no MediaCodec code at all — the Codec port is " +
            "backed only by fakes — so there is nothing to police yet."
    )
}

// `check` is registered by the root build script after this plugin is applied, so bind
// lazily rather than with tasks.named(...).
tasks.matching { it.name == "check" }.configureEach {
    dependsOn(guardStorageWrites)
}
