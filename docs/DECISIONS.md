# DECISIONS

Every place the source documents were ambiguous or silent, and what was
chosen. One line each, newest section last. Anything here is a candidate for
correction by the human — it is a log of judgment calls, not of agreed policy.

## D0 — Documents and inputs

- D0.1 — Only `docs/app-architecture.md` and `docs/frontend-architecture.md` were supplied; `docs/spec.md` and `docs/screens.md` are absent, so Milestone 1 was built from app-architecture (complete) plus frontend-architecture §4.2 (the honest types). Anything Milestone 1 needed from `spec.md` (concrete thresholds, retention defaults, copy strings) is recorded below as a chosen default to be reconciled when the spec lands.
- D0.2 — `spec.md §12.1` (calibration harness) and `spec.md §8` (delete warning copy) are referenced by later milestones only, so their absence does not block M1.

## D1 — Build and modules

- D1.1 — Kotlin Multiplatform modules declare **only the `jvm()` target** in M1. Adding `androidTarget()` would make `./gradlew check` require an Android SDK, which the milestone's definition of done explicitly forbids. M2 adds the Android target to the same modules.
- D1.2 — Fakes live in a **separate `core/ports-fake` module** rather than Gradle `java-test-fixtures`, because `testFixtures` is not supported by the Kotlin Multiplatform plugin. The module is `api`-visible to test source sets only.
- D1.3 — Module directory layout follows app-architecture §2 (`shared/core/...`) with Gradle project paths `:core:model`, `:core:ports`, and so on.
- D1.4 — Property testing uses **kotest-property** (assertions/framework not adopted; tests stay on `kotlin.test`), as the one "property-testing library" the kickoff prompt allows.
- D1.5 — `core/domain` in M1 contains only the frontend↔pipeline contract interfaces from frontend-architecture §8 plus their pipeline-backed implementations; the ViewModel-facing fakes (`core/domain-test`) are deferred to M5.

## D2 — Model types

- D2.1 — `SkipReason` is sealed over the five reasons in frontend-architecture §4.2 (`AlreadyEfficient`, `TooNoisy`, `Hdr`, `SecondaryTrack`, `TooSmall`) **plus** `NoHeadroom` and `CannotReachTarget`, which app-architecture §3 requires the HeadroomCheck and Prober stages to emit. The two documents disagree on the arity of the set; the union is used because dropping either would make a stage unable to report its outcome.
- D2.2 — `EstimateRange` carries `lowBytes <= highBytes` as a constructor `require`, plus an `EstimateConfidence` of `SEED`/`PREDICTED`/`PROBED`; frontend-architecture §4.2 names the field `confidence` without giving its type.
- D2.3 — `QualityScore` is a value class over a `Double` in `0.0..100.0` tagged with its `Metric` (`XPSNR` or `VMAF`), so an XPSNR value can never be compared against a VMAF threshold by accident.
- D2.4 — Encoder settings are modelled as a **quality index** (`EncodeSetting.quality`, a CRF-like integer where higher is more aggressive) rather than a bitrate, because app-architecture §3 searches CRF and §12/M3 flags CQ-vs-bitrate mode as an open per-device risk. The index is the search variable; mapping it to a device's actual rate-control mode is M3's job.
- D2.5 — `OriginalFate.KeptDays(n)` requires `n >= 1`; a retention of zero days is `Deleted`, which is a different user-visible message.

## D3 — Triage policy

- D3.1 — "Bitrate per pixel per second" is computed as `bitrateBps / (width * height * frameRate)` and called **bpp** below. Thresholds (a file above the threshold is considered bloated enough to be worth compressing): H.264 `0.080`, VP9 `0.055`, HEVC `0.050`, AV1 `0.045`, unknown codecs `0.080`. The documents state the rule but not the numbers; these are seeded from typical phone-camera output and are expected to be re-derived by the M4 calibration corpus.
- D3.2 — Minimum size gate is 8 MiB (`TooSmall`); below it the fixed cost of probe + search + verify outweighs any saving.
- D3.3 — A file is `Hdr` when its transfer function is PQ or HLG, or its bit depth exceeds 8. A file has a `SecondaryTrack` when it carries more than one video track or more than one audio track, or any subtitle/timed-metadata track — the documents say "secondary-track gate" without enumerating.
- D3.4 — Gate order is: already-processed (Scanner, DB) → `SecondaryTrack` → `Hdr` → `TooSmall` → `AlreadyEfficient`. Structural rejections are reported before quality-based ones so the user sees the more explanatory reason.
- D3.5 — Triage's estimate is `EstimateRange` derived from the codec's target bpp with a ±18% band at `SEED` confidence; the Predictor narrows it when it has observations.

## D4 — Search and bracket

- D4.1 — Bracket by source codec (quality index, low = safest): H.264 `20..32`, VP9 `18..30`, AV1 `18..30`, HEVC `16..28`, unknown `20..32`. app-architecture §3 says only "HEVC may need CRF < 18"; the HEVC bracket therefore starts at 16.
- D4.2 — The search target is `qualityTarget + 0.5` (app-architecture §3, "targets score +0.5"), evaluated on XPSNR at 720p with every-5th-frame subsampling.
- D4.3 — The search assumes score is **monotonically non-increasing** in the quality index. This is a physical property of every rate-control mode, is what makes a binary search legal, and is stated as an explicit precondition of `Searcher`; the property test generates only monotone score functions.
- D4.4 — "Most aggressive setting whose score ≥ target": when no setting in the bracket reaches the target, the Prober's early abort has already rejected the file (`CannotReachTarget`), so `Searcher` never has to invent a fallback.
- D4.5 — A predictor hit collapses the search to one confirming probe at the predicted setting; if the confirmation fails the target, the full binary search runs over the bracket below the predicted setting rather than the whole bracket.
- D4.6 — Headroom margin is `qualityTarget + 2.0`: if the source cannot even self-score that high, it is `NoHeadroom`. The documents say "target + margin" without a number.

## D5 — Replacer and safety

- D5.1 — The six steps of app-architecture §6 are modelled as an explicit ordered list of `CommitStep`s so kill-tests can name a step rather than a line number; rollback is the reverse-order `undo` of every step that reported success.
- D5.2 — Steps 5 (media scan) and 6 (undo entry) are **not** rolled back on failure of a later step, because there is no later step; a failure at step 5 leaves the compressed file in place and the undo entry is still written, since the original is in the undo bin and must remain restorable.
- D5.3 — "Source unchanged since snapshot" is checked by (size, last-modified, and a cheap content fingerprint) captured at scan time and re-checked immediately before step 2; the documents say "checksum on commit" for restore only.
- D5.4 — Cancellation between steps 1 and 2 is a clean no-op; from step 3 onward cancellation is refused until the sequence completes, because step 3 is the atomic point and interrupting a rollback is worse than finishing.

## D6 — Runner, scheduling and data

- D6.1 — `RunPolicy` is a pure function of (trigger, battery/charging, thermal reading, nightly byte budget, clock) returning a sealed `RunDecision` (`Proceed`, `Pause(reason)`, `Stop(reason)`); app-architecture §7 describes the policy prose but names no object shape.
- D6.2 — Thermal hysteresis: pause above 0.7 headroom-consumed, resume below 0.5 (§7), with a **minimum pause of 60 s**; the document requires "a minimum pause" without a value.
- D6.3 — Thermal polling is at most every 10 s (§7); the first reading of `0` or `NaN` latches `Unsupported` for the process lifetime and the runner falls back to coarse thermal status.
- D6.4 — Jobs are claimed with a transactional compare-and-set on `jobs.state` so a killed process never double-runs a file (§4.1); a claim carries a `claimed_at` and the startup reconciler releases claims older than the process that made them.
- D6.5 — The startup reconciler repairs four mismatch classes: undo entry with no file, file in the bin with no entry, job claimed by a dead process, and history row whose undo entry expired. Each repair is recorded, never silent (§10, "no failure mode may be invisible").
- D6.6 — SQLDelight runs against the JVM SQLite driver in M1; the Android driver arrives with M2. The schema is shared and versioned from `1.sqm` onward.
- D6.7 — Progress is throttled to 2 Hz *at the boundary the UI consumes* (frontend-architecture §9), so the pipeline emits `CompressPhase` unthrottled and `core/domain` applies the throttle; this keeps the runner's tests deterministic.

## D7 — Build guards

- D7.1 — Guard #3 (storage writes only in `Replacer`) is implemented as a Gradle verification task that parses every Kotlin source set for references to the write-capable `Storage` members, which are annotated `@StorageWrite` in `core/ports` so the guard has an authoritative list rather than a hand-maintained one.
- D7.2 — Every guard fails when it finds **nothing to scan** (app-architecture §8), so an empty source set or a renamed module can never make a guard silently pass.
- D7.3 — Guards #1 (no network) and #2 (codecs only via `CodecFactory`) are registered now as tasks that fail loudly with a `TODO(M2)` message, so `./gradlew check` does not run them but any attempt to rely on them is impossible to miss.
