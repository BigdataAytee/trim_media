# Trim — Application Architecture

*The system as a whole: layers, modules, data flow, the compression pipeline as components, concurrency, storage safety, scheduling, and the build-enforced invariants. A restructured, expanded replacement for §9 of the product spec. Companion to the Frontend Architecture document.*

---

## 1. Architecture at a glance

Kotlin Multiplatform. Roughly 75% of the code is shared, platform-free Kotlin; the remaining 25% is exactly the code that touches hardware, storage, or the OS scheduler, isolated behind interfaces.

```
┌──────────────────────────────────────────────────────────────┐
│ PRESENTATION      Compose Multiplatform screens + ViewModels │
│                   (see Frontend Architecture doc)            │
├──────────────────────────────────────────────────────────────┤
│ DOMAIN            use cases · entities · policy              │
│                   "what should happen" — pure Kotlin         │
├──────────────────────────────────────────────────────────────┤
│ PIPELINE          scan → triage → probe → search → encode →  │
│                   verify → replace — pure logic orchestrating │
│                   platform ports                             │
├──────────────────────────────────────────────────────────────┤
│ DATA              SQLDelight DB · settings · predictor store │
├──────────────────────────────────────────────────────────────┤
│ PLATFORM PORTS    Codec · Scorer · Storage · Scheduler ·     │
│ (interfaces)      Thermal · MediaInfo · Clock               │
├──────────────────────────────────────────────────────────────┤
│ PLATFORM IMPLS    Android: MediaCodec/Media3, SAF,           │
│                   WorkManager, PowerManager thermal          │
│                   iOS (later): VideoToolbox, PhotoKit,       │
│                   BGProcessingTask                           │
│                   Native: XPSNR + libvmaf via one C ABI      │
└──────────────────────────────────────────────────────────────┘
```

**Dependency rule:** arrows point downward only. Presentation knows domain; domain knows pipeline interfaces; nothing above the ports layer imports a platform class. Enforced with a dependency-analysis check in CI, not convention.

---

## 2. Module layout

```
shared/
  core/model         entities: Video, Candidate, EstimateRange, SkipReason,
                     CompressPhase, OriginalFate, QualityScore, FolderMode
  core/domain        use-case interfaces + implementations; policy objects
  core/data          SQLDelight schema + DAOs; settings store; predictor table
  core/pipeline      the seven pipeline stages as pure classes; the job runner
  core/ports         Codec, Scorer, Storage, Scheduler, Thermal, MediaInfo, Clock
  core/ui            design system (frontend doc §7)
  feature/*          screens (frontend doc §3)
  native/            C sources: xpsnr/, libvmaf/ — one C ABI, arm64 + NEON
androidApp/          port implementations + manifest + WorkManager workers
iosApp/              port implementations (later)
buildLogic/          convention plugins + the three guard checks (§8)
```

Every decision-making class lives in `shared/` and runs in a JVM unit test with fakes. Every port has exactly one production implementation per platform and one fake.

---

## 3. The pipeline as components

Each stage is a class with one public function, taking values and ports, returning a sealed result. The runner composes them; no stage knows its neighbours.

| Stage | Input → Output | Ports used | Key policy |
|---|---|---|---|
| **Scanner** | folder grants → `Video` rows | Storage, MediaInfo | header-only; no decode; skips already-processed via DB |
| **Triage** | `Video` → `Candidate` \| `Skipped(reason)` | — (pure) | bitrate judged per pixel per second; codec rules; HDR/secondary-track/size gates |
| **NoiseCheck** | `Candidate` → pass \| `Skipped(TooNoisy)` | Codec (decode few frames) | high-frequency-energy estimate; runs before any encode |
| **HeadroomCheck** | `Candidate` → pass \| `Skipped(NoHeadroom)` | Scorer | VMAF self-score; skip if ceiling < target + margin |
| **Prober** | `Candidate` → `Bracket` \| `Skipped(CannotReachTarget)` | Codec, Scorer | early abort: one encode at best-quality end; codec-dependent bracket (HEVC may need CRF < 18) |
| **Searcher** | `Bracket` → `WinningSetting` | Codec, Scorer, Predictor | binary search on XPSNR at 720p, every-5th-frame; predictor collapses to 1 confirming probe; targets score +0.5 |
| **Encoder** | `WinningSetting` → temp file | Codec | hardware only; audio stream-copied; colour range preserved; metadata carried; 2 s keyframes; front-index MP4; `KEY_PRIORITY = 1` |
| **Verifier** | temp file → pass \| reject | Scorer, MediaInfo | tiered: 1-window VMAF on wide XPSNR margin, 3-window when borderline; checks smaller, duration, source unchanged since snapshot |
| **Replacer** | verified file → committed | Storage | the only writer to user storage; six-step atomic sequence (§6) |
| **Predictor** | observations ⇄ suggestions | data | `(device, camera, codec, res, fps, bitrate-bucket) → setting`; also powers instant hub estimates; ships with per-device seed table |

Sealed results mean the runner's state machine is exhaustive — a stage cannot fail in a way the runner doesn't handle, because unhandled outcomes don't compile.

---

## 4. Data flow

### 4.1 The database is the spine

SQLDelight tables: `videos`, `candidates`, `jobs`, `history`, `undo_entries`, `predictor`, `folder_modes`, `settings`.

- The **scan writes** candidates; the **UI observes** them (Flow queries). The hub never asks the pipeline anything directly — it renders the DB.
- The **runner claims** jobs transactionally, so a killed process never double-runs a file.
- **History and undo** are rows first, files second: the undo bin is reconciled against `undo_entries` on every start, and orphans on either side are repaired.

### 4.2 A file's life

```
scan → candidates row (estimate from predictor; instant hub display)
     → user or scheduler queues a job
     → runner: noise → headroom → probe → search → encode(temp) → verify
     → Replacer commits atomically
     → history row + undo entry; predictor learns (setting, outcome)
     → hub estimate for similar files sharpens
```

Every terminal state — done, or skipped with a `SkipReason` — is recorded. Nothing exits the pipeline silently.

---

## 5. Concurrency model

- **One structured-concurrency scope per job**, owned by the runner; cancelling a job cancels everything under it, and cleanup of temp files is a `finally`, not a hope.
- **Silicon pipelining, single-file encoding.** The hardware encoder handles exactly one file at a time (parallel encodes share one encoder block — pure loss). But the CPU **scores file N−1 while the encoder runs file N**, since scoring is CPU/NEON and encoding is dedicated silicon.
- **Zero-copy encode path.** Decoder output Surface wired directly into encoder input Surface; frames never enter CPU memory during the full-file encode. Probe windows are decoded once into cached YUV buffers and reused across the search.
- **Thread placement:** scoring threads run at background priority so the scheduler keeps them on little cores; nothing in the app ever touches the main thread except composition.
- **Progress bus:** the runner emits `CompressPhase` values on a shared flow, throttled to 2 Hz before the UI; WorkManager's foreground notification consumes the same flow, so notification and screen can never disagree.

---

## 6. Storage safety — the Replacer

The single class permitted to write to user storage. Commit sequence, in strict order:

1. Copy metadata from the original into the new file (creation time, GPS, rotation, camera tags).
2. Move the original to its destination per folder mode — undo bin, offload volume, or trash. Same volume where possible, so this is an instant rename.
3. Rename the new file over the original's path and name.
4. Restore the original's last-modified timestamp — and verify `DATE_TAKEN` survives the media rescan, since galleries key off different fields.
5. Trigger the media scan.
6. Write the undo entry.

Any failure rolls back in reverse order. Two invariants fall out: **the original is never lost** (it exists at every intermediate step), and **cancel is always safe** (nothing user-visible changes until step 3, which is atomic).

Restore is the mirror image, driven from `undo_entries`, and refuses to run if the compressed file has since been edited by another app (checksum on commit).

---

## 7. Scheduling, power, and heat

Two entry points, one policy object (`RunPolicy`, pure Kotlin, fully unit-tested):

| Path | Trigger | Constraints |
|---|---|---|
| **Nightly** | WorkManager periodic | charging + idle, OS-enforced; optional wait-for-full-charge; stop-before-alarm; nightly byte cap |
| **User-initiated** | explicit tap (instant compress, or opt-in "work while I use my phone") | allowed on battery; best-effort codec priority; yields to any foreground app needing the encoder |

Thermal management is **predictive and duty-cycled**:

- Poll `getThermalHeadroom` (with forecast) at most every 10 s; at first call returning 0/NaN, mark the API unsupported and fall back to coarse thermal status.
- Pause above 0.7 headroom-consumed, resume below 0.5, with a minimum pause so an oscillating signal can't cause stutter-stepping.
- Prefer planned duty cycles (encode hard, rest, repeat) over running flat-out into throttling — a throttled chip finishes the night's queue slower than a duty-cycled one.
- Codec priority 1 means Android reclaims the encoder for any foreground app instantly; the runner catches the codec-lost exception, waits, and resumes the file from its last sync point.

No app logic can drain the battery in the nightly path for a structural reason: unplugging kills the process via WorkManager constraints — the app isn't running to have a bug.

---

## 8. Build-enforced invariants

Three rules checked by the build itself so they cannot rot, each implemented as a Gradle verification task that **fails if it finds nothing to scan** (a guard that silently passes is a guard that silently died):

1. **No network.** No `INTERNET` permission in any variant's *merged* manifest; no networking API referenced in any source set. This already caught a third-party dependency silently contributing the permission. The privacy claim in the store listing is literally compile-checked.
2. **Codecs only via `CodecFactory`.** The hardware-only rule is one lint check, not N call-site reviews. Software encoder classes are banned imports.
3. **User-storage writes only in `Replacer`.** Everything else holds read-only handles; write-capable storage APIs outside the Replacer's package fail the build.

Plus the layering check from §1: platform imports above the ports layer fail CI.

---

## 9. Native layer

- **XPSNR** and **libvmaf**, compiled for arm64 with NEON, behind **one C ABI** that accepts planar YUV buffers — never file paths — so Kotlin owns all I/O and the native code is a pure function of memory in, score out.
- Scoring is normalised (both sides scaled to 1920-wide) and subsampled (every 5th frame searching, every 3rd verifying); the search additionally scores at 720p.
- The XPSNR↔VMAF-95 calibration table is data, not code: shipped per device class, updatable without touching the native layer, and flagged loudly in diagnostics when a device falls back to the generic curve.

---

## 10. Error taxonomy

Every failure is one of four kinds, and each kind has one owner:

| Kind | Examples | Owner | User sees |
|---|---|---|---|
| **Expected skip** | too noisy, already efficient, no headroom, HDR | Triage/checks | reason in the "can't be shrunk" list |
| **Retryable interruption** | codec reclaimed, thermal pause, unplugged | Runner | job resumes; notification notes the pause |
| **File-level failure** | verify failed, source changed mid-encode, encoder error | Verifier/Runner | original kept untouched; row in History's skipped list |
| **Invariant breach** | replace rollback triggered, DB/undo-bin mismatch | Replacer/startup reconciler | nothing lost; diagnostics record for the opt-in export |

The rule: **no failure mode may cost the user a file, and no failure mode may be invisible.** Both halves are tested — the fake platform layer can inject every row of this table.

---

## 11. Testing map

| Layer | Against | Runs |
|---|---|---|
| Triage, RunPolicy, search maths, Replacer sequence | pure inputs | JVM, milliseconds |
| Pipeline runner | all-fake ports with scripted failures (codec lost mid-file, thermal storm, storage full, source modified) | JVM |
| Predictor | recorded real-world observation logs | JVM |
| Port implementations | contract tests: one shared test suite per port interface, run against both the fake and the real implementation on-device | device farm |
| Metric calibration | golden-file corpus: real clips with known VMAF/XPSNR pairs per device class | device farm, release gate |
| End-to-end | scan → compress → verify → replace → restore on a seeded media folder | device farm, release gate |

The contract-test pattern is what keeps 75% sharing honest: if the fake and the real implementation both pass the same suite, tests against fakes are evidence, not comfort.

---

## 12. Deliberate constraints (what this architecture refuses to do)

- **No network layer exists to be misused** — not disabled, absent, and build-verified absent.
- **No software encoding fallback** — a file the hardware can't handle is skipped with a reason, never ground out on the CPU.
- **No second write path** — features that want to touch storage must go through the Replacer or not ship.
- **No re-processing** — the processed list is a hard gate; generational loss is prevented structurally, not by warning copy.
- **No cloud, account, or telemetry** — diagnostics are a local file the user exports by hand.

These are the constraints that make the marketing claims ("can't lose a file", "no internet, ever", "won't drain your battery") checkable statements about the codebase rather than promises about behaviour.
