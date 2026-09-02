# Milestone 2 completion prompt

Paste the block below into Claude Code, in a session that has the Android SDK.
Run `tools/preflight-android.sh` first; if it does not exit 0, stop and fix the
environment rather than the code.

Milestone 2 is **partly done** — see `docs/M2-STATUS.md` for what already landed and is
green. This prompt covers only what is left, which is everything that needs a device.

---

```
Read docs/app-architecture.md (§3, §4, §5, §6, §7, §8), docs/M2-STATUS.md, CLAUDE.md and
docs/DECISIONS.md (D1.1, D6.4, D6.6, D6.7, D9.1, D9.2, D9.5) before writing any code.
These documents are the source of truth. Where they conflict with your instincts, the
documents win. Where they conflict with each other, ask me.

You are finishing Milestone 2 of Trim: the Android skeleton and the three real platform
ports. The decision-making pipeline is done, tested and platform-free; this milestone is
plumbing it into an operating system, and the interesting part is not writing the ports —
it is finding out which of the pipeline's assumptions Android declines to honour.

Half of Milestone 2 is already done and must not be redone: core/ports-contract holds one
shared contract suite per port, every fake passes it, and guards #1a and #2 are live and
wired into `check`. Read docs/M2-STATUS.md before starting.

## Scope — build exactly this, nothing more

1. The Android target on the existing modules.

   buildLogic/src/main/kotlin/trim.kmp-library.gradle.kts declares jvm() only, with a
   comment marking the spot. Adding androidTarget() there covers every core/* module at
   once. core/data additionally needs the SQLDelight Android driver alongside its JVM one;
   its build file already separates jvmMain from commonMain (D6.6).

   DECISIONS D1.1 records that JVM-only was a Milestone 1 constraint, not a preference.
   This is the change that lifts it — and it costs the property that `./gradlew check`
   runs without an SDK. The successor property, which you must keep, is that
   `./gradlew jvmTest` still passes without a device. The 130 existing tests are worth
   having precisely because they need no emulator.

2. androidApp, with a manifest that has no INTERNET permission and no networking
   dependency. Guard #1a is already live and will police androidApp's sources the moment
   they exist.

3. The three real ports. Each is finished when it passes the contract suite its fake
   already passes — subclass the suite, supply the fixture, run on a device. The
   assertions are written; do not write new ones for the ports themselves.

   MediaInfo — MediaExtractor / MediaMetadataRetriever, satisfying MediaInfoContract.
   The fixture supplies a readable video, something that is not a video, and a missing
   ref. Header-only: the Scanner runs this over an entire gallery, so it must not open a
   decoder (§3).

   Storage — SAF (DocumentsContract / DocumentFile), satisfying StorageContract. The
   fixture supplies a granted folder, seedVideo, missingRef and seedTemp.

   Expect StorageContract's last clause — "the six-step commit keeps the original
   recoverable at every step" — to be the one that fails first. §6 says the original moves
   to its destination "same volume where possible, so this is an instant rename", and on
   SAF a cross-volume move is not a rename at all: it is a copy-then-delete, with a window
   where the file exists twice and a failure mode where it exists nowhere. Put the undo bin
   on the same volume as the original, and if you cannot make the clause pass as written,
   bring it to me rather than relaxing it. That clause is the storage half of the promise
   that Trim cannot lose a file.

   Scheduler — WorkManager with charging + idle constraints, satisfying SchedulerContract.
   isNightlyScheduled() must be answered from WorkManager's own WorkInfo query and not from
   a flag the implementation keeps (D9.2). A flag passes the clause while proving nothing
   about the OS-level state, which is the only state that matters.

   Note on guard #3: implementing Storage's write-capable members is a declaration, not a
   call, so the guard will not fire on the implementation. It will fire the moment anything
   outside the Replacer's package calls one — including your worker. That is intended.

4. The worker. Wire JobRunner into a WorkManager worker with a foreground progress
   notification fed by the CompressPhase flow.

   - RunQueue in core/domain is the composition to call. TrimApp, in core/domain's jvmTest,
     is the assembly to mirror — it is already the composition root, over fakes.
   - Throttle to 2 Hz at the boundary the UI consumes, not in the runner (D6.7). The
     notification and the screen consume the same flow, so they cannot disagree (§5).
   - Every phase has a name. CompressPhase.Paused carries a PauseReason whose displayText
     is written; the notification shows that text, not a spinner.
   - WorkManager enforces charging and idle at the OS level, so RunPolicy may look
     redundant. It is not: the OS can only kill the process, while RunPolicy is what lets a
     pause say why it happened. Keep both.
   - Android 14 requires a foreground service type. Pick one, and record which and why in
     DECISIONS — it is a store-review-visible choice.

5. Guard #1 — the merged manifest. THIS is what the original brief means by guard #1:
   "no-network on the merged manifest of every variant" (DECISIONS D10.1). The already-live
   guardNoNetworkSources is an extra and does NOT satisfy this milestone; do not mistake a
   green source scan for a discharged requirement. guardNoNetworkManifest is a
   loudly-failing stub in buildLogic/src/main/kotlin/trim.guards.gradle.kts. Implement it
   for real:

   - Check the MERGED manifest of every variant, not the source manifest. §8 notes this
     guard "already caught a third-party dependency silently contributing the permission",
     which is exactly what a source manifest does not show.
   - Fail if it finds no variants to check. A guard that silently passes is a guard that
     silently died, and every other guard in this repo already obeys that rule.
   - Show me it failing on a deliberate <uses-permission android:name="android.permission.
     INTERNET"/>, then remove it and add the transcript to docs/GUARDS.md beside the other
     three.
   - Wire it into `check`, and update the guard table in docs/GUARDS.md.

## Non-goals — do not build these

- No UI. That is Milestone 5. A notification is not a UI; do not add an Activity beyond
  whatever is needed to request SAF grants.
- No real Codec and no real Scorer. The fakes remain in use on-device — that is what makes
  this milestone testable at all. Real codec work is Milestone 3 (docs/M3-PROMPT.md), real
  scoring is Milestone 4.
- No new dependencies beyond AGP, androidx.work, androidx.documentfile and the SQLDelight
  Android driver without asking me first.

## How to work

- In the order above. The Android target first, then one port at a time with its contract
  green on a device before starting the next, then the worker, then the guard.
- Run the contract suites on a device after every port, not at the end.
- Commit per completed unit. Never squash away the history of anything touching storage.
- Record every judgment call in docs/DECISIONS.md, one line each, continuing the
  D-numbering.

## Definition of done

- MediaInfo, Storage and Scheduler each pass their contract suite on a device — the same
  clauses, unmodified, that the fakes pass on the JVM. If a clause had to change, that is a
  conversation, not a commit.
- The fakes still pass their suites too. The Milestone 5 demo build runs entirely on fakes,
  so a fake going stale is a real regression.
- A real job runs end to end on a device through the worker — scan, queue, run, replace —
  with the fake Codec and Scorer, and the notification names every phase it passes through.
- guardNoNetworkManifest is live, wired into `check`, and demonstrated failing.
- guardNoNetworkSources, guardCodecFactoryOnly and guardStorageWrites are all still green,
  now policing androidApp's sources as well.
- `./gradlew jvmTest` still passes on a machine with no device attached.
- docs/M2-STATUS.md is updated to say what is now done, or deleted if nothing is left.

## Bring these to me rather than deciding them

- StorageContract's six-step clause failing on SAF. That is the file-safety promise; a
  relaxed clause is a quieter promise.
- Anything that makes you want to change a port interface. The pipeline's 130 tests are
  written against those shapes.
- The foreground service type, if the obvious choice looks likely to attract store review.

Start by reading the documents and running tools/preflight-android.sh, then show me your
plan as a short ordered task list before writing code.
```
