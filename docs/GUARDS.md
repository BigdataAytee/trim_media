# Build-enforced invariants

app-architecture §8 asks for three rules checked by the build itself, each implemented as
a Gradle verification task that **fails if it finds nothing to scan** — because a guard
that silently passes is a guard that silently died.

| Guard | Task | Status |
|---|---|---|
| #1a no networking API in any source set | `guardNoNetworkSources` | **live**, wired into `check` |
| #1b no `INTERNET` in any merged manifest | `guardNoNetworkManifest` | stub, fails loudly. TODO(M2) |
| #2 codecs only via `CodecFactory` | `guardCodecFactoryOnly` | **live**, wired into `check` |
| #3 user storage written only by the Replacer | `guardStorageWrites` | **live**, wired into `check` |

Guard #1 is **two tasks, not one**. Its two obligations have different requirements: the
source scan needs only sources and is live today; the manifest check needs the Android
Gradle Plugin. Splitting them means "guard #1 passes" can never quietly come to mean "half
of guard #1 passes".

## Guard #3 is real, demonstrated

The guard does not keep a hand-maintained list of write methods. It reads the
`@StorageWrite` annotations off the `Storage` port, so adding a write-capable member
extends the guard automatically.

**Passing, on a clean tree:**

```
$ ./gradlew guardStorageWrites
guardStorageWrites: 7 write-capable member(s), 45 source file(s) policed, no violations.
BUILD SUCCESSFUL
```

**Failing, on a deliberate violation.** A class was added to `core/domain` calling two
write-capable members, then removed:

```kotlin
// shared/core/domain/src/commonMain/kotlin/dev/trim/domain/SneakyWriter.kt
internal class SneakyWriter(private val storage: Storage) {
    suspend fun tidyUp(ref: StorageRef) {
        storage.moveOriginal(ref, OriginalDestination.Trash)
        storage.deleteWritten(ref)
    }
}
```

```
$ ./gradlew guardStorageWrites
* What went wrong:
Execution failed for task ':guardStorageWrites'.
> User storage may be written only by the Replacer (CLAUDE.md invariant, app-architecture
  §6/§8). 2 violation(s):
    .../dev/trim/domain/SneakyWriter.kt:10: calls Storage.moveOriginal — only the Replacer
      may write to user storage
    .../dev/trim/domain/SneakyWriter.kt:11: calls Storage.deleteWritten — only the Replacer
      may write to user storage
```

**Failing when it has nothing to scan.** The guard was pointed at a directory that does
not exist, as a renaming refactor would:

```
$ ./gradlew guardStorageWrites
* What went wrong:
Execution failed for task ':guardStorageWrites'.
> guardStorageWrites found no @StorageWrite members in the ports sources. Either the
  Storage port lost its annotations or this guard is pointed at the wrong directory.
  A guard with nothing to enforce is a dead guard.
```

Both violations were removed and the guard is green again.

## Guard #1a is real, demonstrated

**Passing, on a clean tree:**

```
$ ./gradlew guardNoNetworkSources
guardNoNetworkSources: 18 banned reference(s), 63 source file(s) policed, no violations.
BUILD SUCCESSFUL
```

**Failing on a deliberate violation.** A telemetry object was added to `core/domain`, then
removed:

```kotlin
import java.net.HttpURLConnection
import java.net.URL

internal object Telemetry {
    fun report(event: String) {
        val connection = URL("https://example.invalid/collect").openConnection()
                as HttpURLConnection
        connection.requestMethod = "POST"
    }
}
```

```
$ ./gradlew guardNoNetworkSources
* What went wrong:
Execution failed for task ':guardNoNetworkSources'.
> Trim has no network layer to be misused — not disabled, absent, and build-verified absent
  (app-architecture §12). The privacy claim in the store listing is literally
  compile-checked, so a networking reference in any source set is a broken promise to the
  user, not a style violation.

  4 violation(s):
    .../dev/trim/domain/Telemetry.kt:3: imports java.net.HttpURLConnection (banned: java.net)
    .../dev/trim/domain/Telemetry.kt:3: references HttpURLConnection
    .../dev/trim/domain/Telemetry.kt:4: imports java.net.URL (banned: java.net)
    .../dev/trim/domain/Telemetry.kt:10: references HttpURLConnection
```

Note line 10: the guard bans bare symbols as well as imports, so a fully-qualified
reference that never appears in an import statement is caught too.

**Failing when its ban list is emptied.** Someone "temporarily" clearing the list is the
other way a guard dies:

```
$ ./gradlew guardNoNetworkSources
* What went wrong:
Execution failed for task ':guardNoNetworkSources'.
> guardNoNetworkSources has nothing on its ban list, so it can never fail. A guard that
  cannot fail is not a guard (app-architecture §8).
```

## Guard #2 is real, demonstrated

Live before the code it polices exists, deliberately: it is the M3 Codec work's seatbelt,
and a seatbelt fitted afterwards protects nobody. It bans the platform codec APIs, Media3's
own codec selection (which would route around the factory), and every software transcoder —
§12 says a file the hardware cannot handle is skipped with a reason, so there is no fallback
to import and importing one is always a mistake.

```
$ ./gradlew guardCodecFactoryOnly
guardCodecFactoryOnly: 13 banned reference(s), 63 source file(s) policed, no violations.
```

**Failing on a deliberate violation**, since removed:

```kotlin
import android.media.MediaCodec

internal object QuickEncode {
    fun encoder(): MediaCodec = MediaCodec.createEncoderByType("video/hevc")
}
```

```
$ ./gradlew guardCodecFactoryOnly
* What went wrong:
Execution failed for task ':guardCodecFactoryOnly'.
> Hardware codecs are obtained ONLY via CodecFactory (CLAUDE.md invariant, app-architecture
  §8 guard #2). The hardware-only rule is one lint check, not N call-site reviews — and
  there is no software encoder fallback to fall back to: a file the hardware cannot handle
  is skipped with a reason (§12).

  2 violation(s):
    .../pipeline/encode/QuickEncode.kt:3: imports android.media.MediaCodec
      (banned: android.media.MediaCodec)
    .../pipeline/encode/QuickEncode.kt:7: references MediaCodec.createEncoderByType
```

The guard's single allow-list entry is `androidApp/src/main/kotlin/dev/trim/android/codec/`.
That directory does not exist yet; when `androidApp` adds `CodecFactory`, that is the path
it must live at.

## The stubs fail loudly

```
$ ./gradlew guardNoNetwork
* What went wrong:
Execution failed for task ':guardNoNetwork'.
> TODO(M2): guard "no-network" is not implemented.

  Guard #1 checks the MERGED manifest of every Android variant and bans networking imports
  in every source set. Milestone 1 has no androidApp and no merged manifest to check, so
  implementing it now would give a guard with nothing to scan — which §8 says must fail
  rather than pass.

  This task exists so the guard cannot be forgotten. It fails on purpose.
```

Only `guardNoNetworkManifest` remains a stub. It is deliberately **not** wired into
`check`, so the build stays green; running it is how you find out it is still a stub.

## The exemptions, in full

A guard's allow-list growing quietly is how a guard rots, so every exemption is listed
here as well as in the build script.

| Guard | Exempt path | Why |
|---|---|---|
| #3 | `shared/core/ports/` | the declaration of the write methods themselves |
| #3 | `…/pipeline/replace/` | the Replacer and the Restorer — the package §6 permits |
| #3 | `…/contract/StorageContract.kt` | exercising the write methods *is* its purpose; a contract test for a write that may not call the write tests nothing. Deliberately one **file**, not the module — the other contracts do not touch user storage and must not start |
| #1a | *(none)* | nothing in this repository may reference a networking API |
| #2 | `androidApp/…/android/codec/` | the future home of `CodecFactory`, the one place permitted to touch the platform codec APIs |

## Two things the Replacer's package contains

The guard's allow-list is a package, not a class: `dev.trim.pipeline.replace`. It holds the
`Replacer` and the `Restorer`, which app-architecture §6 calls "the mirror image" — restore
puts a user's file back, so it is a write, and it belongs behind the same guard.
