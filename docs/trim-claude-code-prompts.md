# Building Trim with Claude Code

Three parts: **(A)** one-time setup, **(B)** the kickoff prompt to paste into Claude Code, **(C)** a `CLAUDE.md` file to put in the repo root so every future session keeps the rules. Do A once, paste B, and use the follow-up prompts at the end for each next milestone.

---

## A. One-time setup (before the first prompt)

1. Create an empty git repo, e.g. `trim/`.
2. Copy the four documents into `trim/docs/`:
   - `docs/spec.md` — the full product spec
   - `docs/screens.md` — the revised §10 screens document
   - `docs/frontend-architecture.md`
   - `docs/app-architecture.md`
3. Copy the `CLAUDE.md` from Part C into the repo root.
4. Open Claude Code in the repo and paste the prompt from Part B.

Why this matters: Claude Code reads files in the repo. Giving it the documents as files instead of pasting them into chat means every session — today and next month — works from the same source of truth.

---

## B. The kickoff prompt (paste this into Claude Code)

```
Read docs/spec.md, docs/app-architecture.md, docs/frontend-architecture.md,
and docs/screens.md in full before writing any code. Also read CLAUDE.md.
These documents are the source of truth. Where they conflict with your
instincts, the documents win. Where they conflict with each other, ask me.

You are building Milestone 1 of Trim, an Android video compressor.
Milestone 1 is NOT the app. It is the platform-free core: the entire
decision-making pipeline as pure Kotlin, running and tested on the JVM
against fake platform ports, with zero Android dependencies. If Milestone 1
is right, the app is mostly plumbing; if it is wrong, nothing else matters.

## Milestone 1 scope — build exactly this, nothing more

1. Gradle Kotlin Multiplatform project skeleton matching the module map in
   docs/app-architecture.md §2 (create only: core/model, core/domain,
   core/data, core/pipeline, core/ports, plus buildLogic). No androidApp,
   no UI, no native code yet.

2. core/model — all entities from app-architecture §2, with the honest
   types from frontend-architecture §4.2: EstimateRange, SkipReason
   (sealed), CompressPhase (sealed), OriginalFate (sealed), QualityScore.
   Make invalid states unrepresentable; e.g. there must be no way to
   construct a skip without a reason.

3. core/ports — the seven interfaces from app-architecture §1/§2
   (Codec, Scorer, Storage, Scheduler, Thermal, MediaInfo, Clock), each
   with a configurable fake in a testFixtures/fake module. Fakes must be
   scriptable: injectable delays, failures at any point, thermal
   oscillation, codec-reclaimed mid-encode, source-file-changed mid-job.

4. core/pipeline — the stages as separate classes per the table in
   app-architecture §3: Scanner, Triage, NoiseCheck, HeadroomCheck,
   Prober, Searcher, Encoder(orchestration only — the fake Codec does the
   "encoding"), Verifier, Replacer, Predictor, and the JobRunner that
   composes them. Implement every policy stated in the docs, especially:
   - triage judges bitrate per pixel per second, never flat Mbps
   - the search bracket depends on source codec (HEVC may need CRF < 18)
   - early-abort probe before any search
   - the Replacer's 6-step commit with reverse-order rollback
     (app-architecture §6) — the original must exist at every
     intermediate step
   - sealed results everywhere so the runner's state machine is
     exhaustive at compile time

5. core/data — SQLDelight schema for the tables in app-architecture §4.1,
   with DAOs, and the startup reconciler that repairs undo-bin/DB
   mismatches.

6. Tests. This is half the milestone:
   - unit tests for triage rules, bracket selection, search convergence,
     and RunPolicy
   - property-based test: for any fake-scored content, the search result
     is the most aggressive setting whose score ≥ target
   - JobRunner tests injecting every row of the error taxonomy in
     app-architecture §10 — assert no path loses the "file" and no path
     exits without a recorded outcome
   - Replacer tests: kill the sequence at each of the 6 steps, assert
     rollback restores the exact prior state
   - a JVM end-to-end test: seeded fake storage with ~15 videos of mixed
     kinds (H.264 bloated, efficient HEVC, noisy, HDR, tiny) → scan →
     queue all → run → assert the exact set of compressed / skipped
     outcomes and the history rows

7. buildLogic — implement guard #3 from app-architecture §8 now (storage
   writes only in Replacer, checked at build time, failing if it finds
   nothing to scan). Stub the other two guards with TODO tasks that fail
   loudly so they can't be forgotten.

## How to work

- Work in this order: model → ports+fakes → one pipeline stage at a time
  with its tests → runner → data → end-to-end. Commit after each green
  test suite with a descriptive message.
- After each stage, run the full test suite and show me the output.
- Keep a running file docs/DECISIONS.md: every place the documents were
  ambiguous and what you chose, one line each. Ask me before deciding
  anything that contradicts a document.
- Do not add libraries beyond: Kotlin stdlib, kotlinx-coroutines,
  kotlinx-serialization, SQLDelight, and a property-testing library.
  Justify anything else to me first.
- Do not write any Android, Compose, MediaCodec, or JNI code in this
  milestone, even scaffolding.

## Definition of done for Milestone 1

- `./gradlew check` passes on a machine with no Android SDK.
- The end-to-end JVM test passes and its assertions match the expected
  outcomes table you will include in the test.
- The Replacer kill-tests pass at every step.
- The build guard for storage writes is live and demonstrably fails when
  I add a storage write outside Replacer (show me this failing, then
  remove the violation).
- docs/DECISIONS.md exists and lists every judgment call.

Start by reading the documents, then show me your plan as a short ordered
task list before writing code.
```

---

## C. `CLAUDE.md` for the repo root

```markdown
# Trim — rules for every Claude Code session

Source of truth: docs/spec.md, docs/app-architecture.md,
docs/frontend-architecture.md, docs/screens.md. Read the relevant one
before changing the code it governs. Documents beat instincts; ask the
human when documents conflict.

## Invariants (never violate, never "temporarily" bypass)
- No network: no INTERNET permission in any merged manifest, no
  networking APIs anywhere. Ever.
- User storage is written ONLY by the Replacer class. Everything else is
  read-only.
- Hardware codecs only, obtained ONLY via CodecFactory. No software
  encoder fallback, no direct MediaCodec instantiation elsewhere.
- The original file must survive every failure path. Any change to the
  Replacer commit sequence requires updating its kill-tests first.
- A file is never processed twice (generational loss). The processed
  list in the DB is a hard gate.
- Every skip/failure carries a reason type. No silent exits from the
  pipeline.
- Estimates shown to users are ranges ("about"); rejections show plain-
  language reasons. These live in the type system — do not weaken the
  types to make a screen easier.

## Architecture rules
- Dependency direction: presentation → domain → pipeline → ports.
  Nothing above ports imports a platform class. core/* stays pure Kotlin,
  JVM-testable.
- Every port has one fake; fakes are scriptable (delays, failures).
  New platform capability = new port + fake + contract test, not an
  Android import in shared code.
- Sealed results for anything that can fail; exhaustive `when`, no `else`
  branches on domain sealed types.
- One hardware encode at a time; scoring of file N−1 may overlap the
  encode of file N. Never parallel encodes.

## Working style
- Tests accompany the code in the same commit. Kill-tests for anything
  touching the replace sequence.
- Run `./gradlew check` before claiming anything is done; paste the
  result.
- Record judgment calls in docs/DECISIONS.md (one line each).
- Do not add dependencies without asking.
- Commit per completed unit with descriptive messages; never squash away
  the history of a safety-relevant change.
```

---

## D. Follow-up prompts for later milestones

Use these one at a time, only after the previous milestone's definition
of done is met.

**Milestone 2 — Android skeleton + real ports (no UI):**
```
Milestone 1 is done. Now create androidApp with real implementations of
MediaInfo (MediaExtractor/MediaMetadataRetriever), Storage (SAF), and
Scheduler (WorkManager with charging+idle constraints), each passing the
same contract-test suite as its fake, run as instrumented tests. Wire the
JobRunner into a WorkManager worker with a progress notification fed by
the CompressPhase flow. Implement build guards #1 (no-network on the
merged manifest of every variant) and #2 (codecs only via CodecFactory)
for real, and show me each one failing on a deliberate violation before
removing it. Still no UI and no codec/scoring implementations — the fake
Codec and Scorer remain in use on-device.
```

**Milestone 3 — real Codec port:**
```
Implement the Codec port with Media3 Transformer / MediaCodec: hardware-
only via CodecFactory, decoder-to-encoder Surface path for full-file
encodes, audio stream-copy, colour-range detection and preservation
(assert output range equals input range), metadata carry-over, 2-second
keyframes, front-index MP4, KEY_PRIORITY=1 with catch-wait-resume on
codec reclaim. Add an instrumented test on a bundled sample clip
asserting: output smaller, duration equal, colour range preserved,
DATE_TAKEN survives a media rescan. Detect and record (in docs/
DECISIONS.md) whether this device supports CQ mode or only bitrate
modes, and adapt the Searcher's parameter accordingly — the docs flag
this as an open risk.
```

**Milestone 4 — real Scorer + calibration harness:**
```
Build the native layer: XPSNR and libvmaf for arm64 with NEON behind one
C ABI taking planar YUV buffers (Kotlin owns all I/O). Then build the
calibration harness from docs/spec.md §12.1: an instrumented run that
takes a folder of sample clips, encodes each at a ladder of settings,
scores every result with BOTH metrics at search subsampling, and writes
a CSV of (clip, setting, xpsnr, vmaf). This harness is how we derive the
XPSNR threshold for VMAF 95 per device. Do not hardcode any threshold;
load it from a data table with a loud diagnostic when a device falls
back to the generic curve.
```

**Milestone 5 — UI:**
```
Build the frontend per docs/frontend-architecture.md and docs/screens.md:
core/ui design system first, then Hub, InstantCompress, Folders, History,
Settings, ShareEntry, each with all documented states, ViewModels tested
on the JVM against fake use cases, and screenshot tests in light and
dark. The demo build runs entirely on fakes with scripted timing. Wire
the real app variant to the real pipeline last.
```

---

## E. Why the prompt is shaped this way

- **Core-first, device-last.** The pipeline logic is 100% JVM-testable by
  design; building it first means months of iteration without an emulator,
  and the riskiest logic (search, replace, error handling) gets the most
  test cycles.
- **Fakes before hardware.** The scriptable fakes force the error taxonomy
  to be handled before real hardware adds its own chaos on top.
- **Guards early and proven-failing.** A guard demonstrated to fail on a
  violation is real; a guard merely written is decoration.
- **One milestone per prompt.** Claude Code does best with a bounded goal,
  an explicit non-goal list, and a checkable definition of done — it will
  otherwise happily scaffold the whole app shallowly.
- **DECISIONS.md.** The documents are thorough but not complete; the log
  turns silent judgment calls into reviewable ones.
```
