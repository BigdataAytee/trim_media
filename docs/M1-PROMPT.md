# Milestone 1 kickoff prompt

**This one is history, not a task.** Milestone 1 is done; this is the prompt that actually
produced it, transcribed verbatim from the original kickoff document so the set in `docs/`
is complete and the code has a stated origin. Pasting it now would rebuild what already
exists.

It is reproduced unedited, including the parts that turned out to be wrong. A prompt
rewritten with hindsight would be a tidier document and a false one — it would claim to be
the instruction that built this repository while being an instruction nobody ever gave.
Where the result diverged, that is recorded underneath rather than smoothed away above.

---

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
```

---

## Where the result diverged from this prompt

Eight places, each recorded in `docs/DECISIONS.md` at the id given. They are worth reading
before writing the next milestone's prompt, because most of them are the prompt being
slightly wrong about the world rather than the code being slightly wrong about the prompt.

1. **Two of the four source documents were never supplied.** The first line asks for
   `spec.md` and `screens.md`; neither has ever been in the repository. Everything M1
   needed from them became a recorded default instead. **D0.1**

2. **"a configurable fake in a testFixtures/fake module" is not buildable.** The Kotlin
   Multiplatform plugin does not support Gradle test fixtures, so the fakes live in a
   separate `core/ports-fake` module. **D1.2**

3. **The property, as stated, contradicts the architecture document.** The prompt asks that
   "the search result is the most aggressive setting whose score ≥ target" for *any*
   content — but §3 requires a predictor hit to collapse the search to one confirming
   probe, which by construction may accept a setting that is not the most aggressive one.
   The optimality property is therefore proven for the cold search, and the predicted
   search is held to a safety property instead: never a setting that misses the target,
   never more aggressive than the optimum. Both are property-tested. **D4.5**

4. **`SkipReason` has seven cases, not five.** frontend §4.2 names five; app-architecture §3
   requires the HeadroomCheck and Prober stages to emit two more. The documents disagree
   about the arity of the set and the union was used, because dropping either would leave a
   stage unable to report its outcome. **D2.1**

5. **`RunPolicy` is in the test list but not the stage list.** Item 6 asks for unit tests
   for it; item 4's stage table does not mention it, and neither does §3. It was invented
   as a pure policy object because §7 describes the behaviour in prose without naming an
   object. **D6.1**

6. **KMP, but with only the `jvm()` target.** "Kotlin Multiplatform skeleton" and "passes on
   a machine with no Android SDK" are in tension: adding `androidTarget()` would have made
   `check` require an SDK. M2 lifts this. **D1.1**

7. **`core/data` depends on `core/pipeline`,** inverting the arrow in §1's layer diagram.
   The pipeline owns the persistence abstractions it needs and data supplies them; the
   diagram's direction would have put a database on the pipeline's test classpath. **D6.8**

8. **Guard #3's allow-list is a package, not a class.** The prompt says "storage writes only
   in Replacer". `Restorer` — which §6 calls the Replacer's mirror image — also writes, so
   the guard permits `dev.trim.pipeline.replace`. A third exemption, one *file* of the
   Storage contract suite, arrived in M2. **D8.1, D9.7**

One more thing the prompt got exactly right, and it is the reason the rest held together:
*"Tests. This is half the milestone."* The final count was 122 tests for M1, and the two
that have since caught real defects were both in that half — the search property test and,
in M2, the port contract suites it made natural to write.
