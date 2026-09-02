# Milestone 4 kickoff prompt

Paste the block below into Claude Code, in a session that has the Android SDK and NDK, and
an arm64 device attached. Run `tools/preflight-android.sh` first.

**Precondition: Milestone 3 must be done.** The calibration harness encodes a ladder of
real settings and scores every result, so it needs the real Codec.

**Missing document.** The original milestone brief builds the harness "from docs/spec.md
§12.1". `docs/spec.md` has never been supplied to this repository (see `docs/DECISIONS.md`
D0.1). app-architecture §9 and §11 specify enough to build it; anything §12.1 would have
pinned down — exact CSV columns, corpus size, the release-gate threshold — is a judgment
call to record rather than a fact to look up. If you have `spec.md`, add it to `docs/`
before starting and this paragraph stops applying.

---

```
Read docs/app-architecture.md (§5, §9, §11, §12), CLAUDE.md, docs/DECISIONS.md (D2.3,
D4.2, D4.3, D4.6, D9.3, D9.4) and docs/M3-PROMPT.md before writing any code. These
documents are the source of truth. Where they conflict with your instincts, the documents
win. Where they conflict with each other, ask me.

You are building Milestone 4 of Trim: the real Scorer, and the calibration harness that
tells us what its numbers mean. Everything until now has taken "XPSNR 41.3 is good enough"
on faith from a straight line through two invented points. This milestone replaces the
faith with measurements, and the measurements are the deliverable — the native code is
just how they get taken.

## Scope — build exactly this, nothing more

1. The native layer. XPSNR and libvmaf compiled for arm64 with NEON, behind ONE C ABI
   that accepts planar YUV buffers and never file paths (§9). Kotlin owns all I/O; the
   native code is a pure function of memory in, score out. That boundary is the whole
   design — it is what keeps the native layer free of permissions, lifecycle and error
   handling, and it is not negotiable for convenience.

   Where the buffers come from is an open question you must resolve before writing the
   ABI. §5 says probe windows are "decoded once into cached YUV buffers and reused across
   the search", so the decode belongs to the Kotlin side and the cache is shared between
   scoring and encoding. The Codec port today returns statistics and handles, not buffers.
   Decide whether the cache lives behind the Codec port, behind the Scorer, or in a third
   thing both use — and bring the answer to me before you build it, because it changes a
   port interface that 130 tests are written against.

   Bound the cache. A 4K frame is ~12 MB of planar YUV; a window of them is not something
   to hold by accident.

2. The real Scorer port, satisfying ScorerContract on a device — the same five clauses
   FakeScorer already passes on the JVM. Subclass dev.trim.ports.contract.ScorerContract,
   supply a fixture, run it on hardware.

   The clause to watch is "a more aggressive setting never scores higher". It compares the
   ends of the bracket rather than adjacent settings, so real metric noise should not trip
   it — but if it does trip, the Searcher's binary search is unsound (D4.3) and that is a
   finding to bring me, not a tolerance to widen.

   Honour §9's normalisation: both sides scaled to 1920-wide, every 5th frame while
   searching and every 3rd while verifying, and the search additionally at 720p. Those
   numbers already live in PipelineConfig — read them from there rather than restating them.

   Scoring threads run at background priority so the scheduler keeps them on little cores
   (§5). The encoder is dedicated silicon; do not let scoring fight the foreground for a
   big core.

3. The calibration harness — the actual point of this milestone. An instrumented run that
   takes a folder of sample clips, encodes each at a ladder of settings, scores every
   result with BOTH metrics at search subsampling, and writes a CSV of
   (clip, setting, xpsnr, vmaf).

   This is how the XPSNR threshold for VMAF 95 gets derived per device. Design it to be
   re-run: a corpus that grows, a CSV that appends, a device column so two phones'
   results can sit in one file. §11 calls the corpus a release gate, so it will be run by
   someone who is not you, on a device you have never seen.

   Record in DECISIONS what §12.1 would have told us and you had to choose instead: the
   ladder's settings, how many clips, what counts as enough of them, and what the CSV's
   columns are.

4. The calibration table stays data, not code (§9). Do not hardcode a threshold to make
   anything pass.

   core/pipeline/calibrate/CalibrationTable.kt already has the shape: per-device-class
   curves, a generic fallback, and a CalibrationLookup that distinguishes the two.
   JobRunner already records the fallback's diagnostic. Your job is to fill it with
   measured curves loaded from a shipped data file, updatable without touching the native
   layer — not to rewrite it. Keep the loud diagnostic when a device falls back; §9 asks
   for it explicitly and it is how we will find out which devices we have never calibrated.

5. Extend guard #1a to the native sources. All three guards scan `*.kt` and nothing else,
   so `native/` would land completely unpoliced — a C file could open a socket and the
   build would pass, which makes "no network layer exists to be misused" (§12) false in
   exactly the place hardest to notice.

   Add C and C++ sources to guardNoNetworkSources with an appropriate ban list
   (sys/socket.h, netdb.h, arpa/inet.h, curl, and friends), show me it failing on a
   deliberate #include, then remove it and add the transcript to docs/GUARDS.md.

6. Pipelining, if it earns its place. §5 promises that "the CPU scores file N−1 while the
   encoder runs file N", and the runner today is strictly sequential — RunQueue takes one
   job at a time and JobRunner runs it to completion. That was fine when scoring was fake
   and instant. It will not be fine now.

   Measure first. If scoring is a small fraction of encode time on a real device, say so
   and leave the runner alone. If it is not, propose the change before making it: it
   touches JobRunner's structured-concurrency scope, and "never parallel encodes" (§5)
   must survive whatever you do.

## Non-goals — do not build these

- No UI. That is Milestone 5.
- No changes to the Codec port beyond whatever the buffer-cache decision forces, and that
  decision comes to me first.
- No software encoder, ever, for any reason, including "just for the harness".
- No new dependencies beyond the two native libraries without asking me first.

## How to work

- Native ABI first, with a JVM-side smoke test that feeds it known buffers and checks the
  scores are plausible, before any of it is wired into the pipeline.
- Then the Scorer port, green against ScorerContract on a device.
- Then the harness, then the table, then the guard.
- Commit per completed unit. Record every judgment call in docs/DECISIONS.md, one line
  each, continuing the D-numbering.

## Definition of done

- The real Scorer passes ScorerContract on a device — the same clauses, unmodified, that
  FakeScorer passes on the JVM. FakeScorer still passes them too; the Milestone 5 demo
  build runs entirely on fakes.
- The harness runs on a device and produces a CSV that someone else could read without
  asking you what a column means.
- At least one real device class has a measured curve in the shipped table, and a device
  outside it still works — falling back to the generic curve with the diagnostic recorded,
  not crashing and not silently guessing.
- No threshold is hardcoded anywhere. Grep for the numbers in the generic curve and
  satisfy yourself they appear in exactly one place.
- guardNoNetworkSources polices native sources, demonstrated failing.
- `./gradlew jvmTest` still passes on a machine with no device attached.
- docs/DECISIONS.md records the buffer-cache decision, the ladder, the CSV format, and
  what the measured curve says versus what the generic one guessed.

## Bring these to me rather than deciding them

- Where the decoded-buffer cache lives. It is a port change.
- ScorerContract's monotonicity clause failing on real metrics.
- Whether pipelining is worth the concurrency it costs.
- The gap between the generic curve and the first measured one, whatever it turns out to
  be. If it is large, every estimate the app has shown so far was wrong in a way we can
  now describe — and I want to know by how much.

Start by reading the documents, then show me your plan as a short ordered task list before
writing code.
```
