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
            // Android. NOT the whole `android.net` package: android.net.Uri is a URI
            // parser, not a networking API, and SAF is built on it — banning the package
            // wholesale would block the Storage port and invite someone to exempt
            // `android.net` entirely, which would silently un-police every Android source
            // file. The networking classes are therefore named one by one.
            "android.net.ConnectivityManager",
            "android.net.Network",
            "android.net.NetworkCapabilities",
            "android.net.NetworkInfo",
            "android.net.NetworkRequest",
            "android.net.LinkProperties",
            "android.net.DnsResolver",
            "android.net.LocalSocket",
            "android.net.LocalServerSocket",
            "android.net.TrafficStats",
            "android.net.VpnService",
            "android.net.Proxy",
            "android.net.SSLCertificateSocketFactory",
            "android.net.wifi",
            "android.net.sip",
            "android.net.http",
            "android.net.rtp",
            "android.net.nsd",
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

// ---- guard #2 ----
//
// Live now, before the code it polices exists, and deliberately so: it is the M3 Codec
// work's seatbelt, and a seatbelt fitted afterwards protects nobody. Today it scans the
// shared modules and finds nothing, which is a pass and not a no-op — it has a ban list and
// sources to scan, which is what §8 requires of a guard that reports success.

val guardCodecFactoryOnly = tasks.register<BannedImportGuardTask>("guardCodecFactoryOnly") {
    group = "verification"
    description = "Guard #2: hardware codecs are obtained only via CodecFactory."

    scannedSources.from(policedSources())
    allowedPathFragments.set(
        listOf(
            // The one place permitted to touch the platform codec APIs. It does not exist
            // yet; when androidApp adds it, this is the path it must live at.
            "androidApp/src/main/kotlin/dev/trim/android/codec/",
        )
    )
    bannedImportPrefixes.set(
        listOf(
            // The platform codec APIs: allowed only behind CodecFactory.
            "android.media.MediaCodec",
            "android.media.MediaCodecList",
            "android.media.MediaCodecInfo",
            // Media3/ExoPlayer's own codec selection, which would route around the factory.
            "androidx.media3.transformer.Codec",
            "androidx.media3.exoplayer.mediacodec",
            // Software encoders. app-architecture §12: a file the hardware cannot handle is
            // skipped with a reason, never ground out on the CPU. There is no fallback to
            // import, so importing one is always a mistake.
            "org.jcodec",
            "com.arthenica.ffmpegkit",
            "com.arthenica.mobileffmpeg",
            "net.ypresto.androidtranscoder",
            "com.otaliastudios.transcoder",
        )
    )
    bannedSymbols.set(
        listOf(
            // Direct instantiation, which an import ban alone would miss when the class is
            // referenced by its fully-qualified name.
            "MediaCodec.createEncoderByType",
            "MediaCodec.createDecoderByType",
            "MediaCodec.createByCodecName",
        )
    )
    rationale.set(
        "Hardware codecs are obtained ONLY via CodecFactory (CLAUDE.md invariant, " +
            "app-architecture §8 guard #2). The hardware-only rule is one lint check, not N " +
            "call-site reviews — and there is no software encoder fallback to fall back to: " +
            "a file the hardware cannot handle is skipped with a reason (§12)."
    )
    report.set(layout.buildDirectory.file("reports/guards/codec-factory-only.txt"))
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(guardStorageWrites, guardNoNetworkSources, guardCodecFactoryOnly)
}
