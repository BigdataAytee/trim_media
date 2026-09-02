import trim.guards.BannedImportGuardTask
import trim.guards.PendingGuardTask
import trim.guards.StorageWriteGuardTask

// The three build-enforced invariants of app-architecture §8. Guard #3 is live in
// Milestone 1; #1 and #2 police Android code that does not exist yet, so they are
// registered as loudly-failing stubs.

val repoRoot = rootProject.layout.projectDirectory

/**
 * Every production Kotlin source in the build, derived from the modules the build actually
 * has — so a module added later is policed automatically rather than quietly exempt. Source
 * directories only, never a tree rooted above a module's `build/`.
 */
fun policedSources(): List<Any> = subprojects.map { module ->
    module.layout.projectDirectory.dir("src").asFileTree.matching {
        include("**/*Main/kotlin/**/*.kt")
    }
}

val guardStorageWrites = tasks.register<StorageWriteGuardTask>("guardStorageWrites") {
    group = "verification"
    description = "Guard #3: user storage is written only by the Replacer."

    annotationName.set("StorageWrite")

    // Source directories only, never a tree rooted above a module's `build/`: a guard whose
    // inputs overlap another task's outputs is a guard Gradle has to serialise around.
    portSources.from(
        repoRoot.dir("shared/core/ports/src").asFileTree.matching { include("**/*.kt") }
    )
    scannedSources.from(policedSources())
    allowedPathFragments.set(
        listOf(
            // The port declaration itself.
            "shared/core/ports/",
            // The package permitted to write (app-architecture §6): the Replacer and its
            // mirror image, the Restorer.
            "core/pipeline/src/commonMain/kotlin/dev/trim/pipeline/replace/",
            // The Storage port's own contract suite. It is exempt because exercising the
            // write methods IS its purpose — a contract test for a write that may not call
            // the write would be a contract test of nothing. Deliberately one FILE, not the
            // module: the other contracts do not touch user storage and must not start.
            "core/ports-contract/src/commonMain/kotlin/dev/trim/ports/contract/" +
                "StorageContract.kt",
        )
    )
    report.set(layout.buildDirectory.file("reports/guards/storage-writes.txt"))
}

// ---- guard #1, in two halves ----
//
// §8's guard #1 has two obligations: no INTERNET permission in any variant's merged
// manifest, and no networking API referenced in any source set. The second is a source scan
// and is live now; the first needs the Android Gradle Plugin to produce a merged manifest
// and stays a loud stub. They are two named tasks rather than one, so that "guard #1
// passes" can never come to mean "half of guard #1 passes".

val guardNoNetworkSources = tasks.register<BannedImportGuardTask>("guardNoNetworkSources") {
    group = "verification"
    description = "Guard #1a: no networking API is referenced in any source set."

    scannedSources.from(policedSources())
    allowedPathFragments.set(emptyList<String>())
    bannedImportPrefixes.set(
        listOf(
            // JDK
            "java.net",
            "javax.net",
            "java.rmi",
            // Android
            "android.net",
            "android.webkit",
            // Kotlin / JetBrains
            "io.ktor",
            // The usual third parties, banned by name so a stray dependency is obvious.
            // §8 notes this guard "already caught a third-party dependency silently
            // contributing the permission" — the source half is where the import shows up.
            "okhttp3",
            "retrofit2",
            "com.squareup.okhttp",
            "org.apache.http",
            "com.android.volley",
            "com.google.firebase",
            "com.google.android.gms",
        )
    )
    bannedSymbols.set(
        listOf(
            "HttpURLConnection",
            "URLConnection",
            "InetAddress",
            "DatagramSocket",
            "ServerSocket(",
        )
    )
    rationale.set(
        "Trim has no network layer to be misused — not disabled, absent, and build-verified " +
            "absent (app-architecture §12). The privacy claim in the store listing is " +
            "literally compile-checked, so a networking reference in any source set is a " +
            "broken promise to the user, not a style violation."
    )
    report.set(layout.buildDirectory.file("reports/guards/no-network-sources.txt"))
}

tasks.register<PendingGuardTask>("guardNoNetworkManifest") {
    group = "verification"
    description = "Guard #1b (stub): no INTERNET permission in any merged manifest. TODO(M2)."
    guardName.set("no-network-manifest")
    milestone.set("M2")
    rationale.set(
        "The other half of guard #1 — guardNoNetworkSources — is live and wired into `check`. " +
            "This half checks the MERGED manifest of every Android variant, which is where a " +
            "third-party dependency silently contributing INTERNET shows up and where a " +
            "source scan cannot look. It needs the Android Gradle Plugin, so it lands with " +
            "androidApp."
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
    dependsOn(guardStorageWrites, guardNoNetworkSources)
}
