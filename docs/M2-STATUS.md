# Milestone 2 — status and handoff

Milestone 2 is **partly done**. The half that needs no Android SDK is finished and green;
the half that does is blocked on the environment, not on any decision.

Run `tools/preflight-android.sh` first. It answers in one command whether the machine you
are on can build the Android half, and names the blocker if not.

`docs/M2-PROMPT.md` turns the "left to build" section below into a kickoff prompt to paste
into Claude Code.

---

## Done, and verified

| Piece | Where | Evidence |
|---|---|---|
| One shared contract suite per port | `shared/core/ports-contract` | 7 suites, ~40 clauses |
| Every fake passes its contract | `core/ports-fake` JVM tests | 8 contract tests, green |
| `Scheduler.isNightlyScheduled()` | `core/ports` | added so the port is testable at all (D9.2) |
| Guard #1a — no networking API in any source set | `guardNoNetworkSources` | live, in `check`, demonstrated failing |
| Guard #2 — codecs only via `CodecFactory` | `guardCodecFactoryOnly` | live, in `check`, demonstrated failing |

`./gradlew check` — 130 tests, 0 failures, no Android SDK required.

The contract suites are the load-bearing piece. Everything left below is finished when its
implementation passes the suite its fake already passes; the assertions are written, so no
new test design is needed for the ports.

Writing them already paid for itself twice: `FakeCodec.encodeFull` looked up content before
checking the source existed, so a vanished source threw instead of returning a named
failure — caught on the suite's first run.

---

## Blocked, and why

`dl.google.com` is denied by this container's egress policy. The Android SDK, the Android
Gradle Plugin and **every** androidx artifact are served only from there —
`maven.google.com` resolves but 301-redirects to the blocked host, so it is not a way
around. There is also no `/dev/kvm`, so no emulator.

Nothing about the repository causes this and no code change works around it.

### What an unblocked environment needs

- **`dl.google.com` allowed** (and `maven.google.com`, which redirects to it). This is the
  whole blocker for *compiling*. The network policy is chosen when a Claude Code
  environment is created — see
  <https://code.claude.com/docs/en/claude-code-on-the-web>.
- **A device or emulator** — only for running `connectedAndroidTest`. The module compiles
  and the unit tests run without one; the instrumented contract runs do not.

---

## Left to build

In dependency order. Each item names where it plugs in, so none of this has to be
rediscovered.

### 1. Add the Android target to the existing modules

`buildLogic/src/main/kotlin/trim.kmp-library.gradle.kts` declares `jvm()` only, with a
comment marking the spot. Adding `androidTarget()` there covers every `core/*` module at
once. `core/data` additionally needs the SQLDelight Android driver alongside its JVM one
(`shared/core/data/build.gradle.kts` already separates `jvmMain` from `commonMain`).

`DECISIONS.md` D1.1 records that the JVM-only target was a Milestone 1 constraint, not a
preference — this is the change that lifts it.

### 2. `androidApp` — the three real ports

Each is finished when it passes the contract its fake passes. Subclass the suite, supply
the fixture, run as an instrumented test:

| Port | Implementation | Contract to satisfy | Fixture must supply |
|---|---|---|---|
| `MediaInfo` | `MediaExtractor` / `MediaMetadataRetriever` | `MediaInfoContract` | a readable video, a non-video, a missing ref |
| `Storage` | SAF (`DocumentsContract`, `DocumentFile`) | `StorageContract` | a granted folder, a way to seed files and temps |
| `Scheduler` | WorkManager, charging + idle | `SchedulerContract` | nothing — the port is self-contained |

Two things the contracts have already decided:

- **`isNightlyScheduled()` must come from WorkManager's own `WorkInfo` query**, not from a
  flag the implementation keeps. A flag would pass the clause while proving nothing about
  the OS-level state (D9.2).
- **`StorageContract` runs the Replacer's six-step sequence against the port itself**,
  asserting the original is recoverable after each step. On SAF that is the clause most
  likely to fail first — `moveOriginal` across volumes is not a rename — and it is exactly
  the thing that must not be discovered later.

### 3. The worker

Wire `JobRunner` into a WorkManager worker with a foreground progress notification fed by
the `CompressPhase` flow. `RunQueue` in `core/domain` is the composition to call; the
end-to-end test's `TrimApp` (in `core/domain`'s jvmTest) is the assembly to mirror — it is
already the composition root, over fakes.

Throttle to 2 Hz **at the boundary the UI consumes**, not in the runner (D6.7): the
notification and the screen consume the same flow and so cannot disagree (§5).

### 4. Guard #1b — the merged manifest

`guardNoNetworkManifest` is a loudly-failing stub in
`buildLogic/src/main/kotlin/trim.guards.gradle.kts`. Implement it against the merged
manifest of **every variant**, not the source manifest — §8 notes this guard already caught
a third-party dependency silently contributing `INTERNET`, which is precisely what a source
manifest does not show. Demonstrate it failing on a deliberate `<uses-permission>`, then
remove it and record the transcript in `docs/GUARDS.md` beside the other three.

### 5. Not in this milestone

The fake `Codec` and `Scorer` stay in use on-device. Real codec work is M3; real scoring is
M4. Guard #2 is already live and will police the Android sources the moment they exist —
its allow-list names `androidApp/src/main/kotlin/dev/trim/android/codec/`, so that is where
`CodecFactory` must live.
