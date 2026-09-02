# Build-enforced invariants

app-architecture §8 asks for three rules checked by the build itself, each implemented as
a Gradle verification task that **fails if it finds nothing to scan** — because a guard
that silently passes is a guard that silently died.

| Guard | Task | Status |
|---|---|---|
| #1 no network | `guardNoNetwork` | stub, fails loudly. TODO(M2) |
| #2 codecs only via `CodecFactory` | `guardCodecFactoryOnly` | stub, fails loudly. TODO(M3) |
| #3 user storage written only by the Replacer | `guardStorageWrites` | **live**, wired into `check` |

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

They are deliberately **not** wired into `check`, so the build stays green; running one is
how you find out it is still a stub.

## Two things the Replacer's package contains

The guard's allow-list is a package, not a class: `dev.trim.pipeline.replace`. It holds the
`Replacer` and the `Restorer`, which app-architecture §6 calls "the mirror image" — restore
puts a user's file back, so it is a write, and it belongs behind the same guard.
