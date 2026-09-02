# Trim — Frontend Architecture

*How the user-facing layer is structured: screens, state, navigation, design system, and the contract between UI and the pipeline underneath. Companion to the App Architecture document.*

---

## 1. Principles

1. **The UI never computes; it renders decisions already made.** Every number on screen (estimates, savings, reasons) comes from the pipeline layer. The frontend has no compression logic, no file access, no codec knowledge.
2. **Unidirectional data flow.** State flows down as immutable objects; user intent flows up as events. No screen mutates anything directly.
3. **One shared UI codebase.** Compose Multiplatform, ~100% of screen code shared between Android and (later) iOS. Platform differences live behind the same interfaces as everything else.
4. **Every state is designed, including the ugly ones.** Loading, empty, error, permission-denied, "nothing compressible found" — each screen defines all of its states up front. There is no default spinner.
5. **Honesty is a UI requirement, not just a copy choice.** Estimates say "about". Rejections show reasons. Progress phases are named. These are enforced by the state model (e.g. `Estimate` is a range type, not a number).

---

## 2. Layer diagram

```
┌────────────────────────────────────────────────┐
│  Screens (Compose Multiplatform)               │
│  Hub · InstantCompress · Folders · History ·   │
│  Settings · ShareEntry                          │
├────────────────────────────────────────────────┤
│  ViewModels (one per screen)                   │
│  UiState (immutable) ⇅ UiEvent (sealed)        │
├────────────────────────────────────────────────┤
│  Use cases (core/domain)                       │
│  ObserveCandidates · CompressNow · QueueAll ·  │
│  SetFolderMode · RestoreOriginal · ...          │
├────────────────────────────────────────────────┤
│  Pipeline + data (not frontend — see App doc)  │
└────────────────────────────────────────────────┘
```

The frontend is the top two layers. It talks only to use cases; it never touches the pipeline, database, or platform APIs directly.

---

## 3. Module map

```
shared/
  core/ui/                    design system (tokens, components, theme)
  feature/hub/                Home + Big files list
  feature/compress/           instant-compress flow + progress
  feature/folders/            folder grants + per-folder original mode
  feature/history/            history, restore, skipped list
  feature/settings/           quality, schedule, work-while-using toggle
  feature/share/              share-sheet entry point
  navigation/                 typed route graph
```

Each `feature/*` module contains its screens, ViewModels, and UiState types, and depends only on `core/ui` and `core/domain`. Features never depend on each other; cross-feature navigation goes through the typed route graph.

---

## 4. State model

### 4.1 Pattern

Each screen follows the same triad:

```kotlin
// Immutable, exhaustively renderable
sealed interface HubUiState {
  data object Scanning : HubUiState
  data class Ready(
    val totalFreeableBytes: EstimateRange,
    val shrinkable: List<CandidateRow>,
    val notShrinkable: List<SkippedRow>,
    val selection: Set<VideoId>,
  ) : HubUiState
  data class NoFolderAccess(val canRequest: Boolean) : HubUiState
  data class NothingFound(val scannedBytes: Long) : HubUiState
}

// User intent, sealed and finite
sealed interface HubUiEvent {
  data class ToggleSelect(val id: VideoId) : HubUiEvent
  data object CompressAllTonight : HubUiEvent
  data object CompressSelectedNow : HubUiEvent
  data class OpenDetail(val id: VideoId) : HubUiEvent
}
```

ViewModels expose `StateFlow<UiState>`, consume events, call use cases, and hold zero platform references — they are pure Kotlin, unit-tested on the JVM with fake use cases.

### 4.2 Honest types

Types that make dishonest UI unrepresentable:

| Type | Guarantees |
|---|---|
| `EstimateRange(low, high, confidence)` | Estimates render as "about X" — there is no single-number estimate type before a probe has run |
| `SkipReason` (sealed: `AlreadyEfficient`, `TooNoisy`, `Hdr`, `SecondaryTrack`, `TooSmall`) | Every rejection carries a user-displayable reason; a bare "skipped" cannot be constructed |
| `CompressPhase` (sealed: `Checking`, `FindingSetting(probesDone)`, `Encoding(pct, eta)`, `Verifying`, `Done(result)`, `Rejected(reason)`) | Progress UI must name its phase; there is no anonymous progress state |
| `OriginalFate` (sealed: `KeptDays(n)`, `Offloaded(volume)`, `Deleted`) | Every completion message states what happened to the original |

### 4.3 Where state lives

| State | Home | Survives |
|---|---|---|
| Screen state | ViewModel `StateFlow` | config change |
| Selection, scroll | ViewModel + `SavedStateHandle` | process death |
| Candidates, history, folder modes | database (observed via use cases) | everything — the DB is the single source of truth; screens are projections of it |
| Live compress progress | pipeline progress bus → use case → ViewModel | job survives UI death (WorkManager); UI reattaches by observing |

The important consequence: killing the app mid-compression loses nothing and desyncs nothing. The UI is a window onto the job, not the owner of it.

---

## 5. Screen catalogue

| Screen | Purpose | Key states |
|---|---|---|
| **Hub** | Headline freeable total, shrinkable list, can't-shrink list, two primary actions | Scanning / Ready / NoFolderAccess / NothingFound |
| **InstantCompress** | Named-phase progress for one file or a batch; original-fate line; cancel | per-phase via `CompressPhase`; batch = ordered list of them |
| **BigFiles** | Full list with filters, multi-select, running total | inherits Hub states + filter state |
| **Folders** | SAF grants; per-folder original mode; include-in-nightly toggle | Empty / Ready / GrantInProgress |
| **History** | Completed items, restore-within-window, skipped list, lifetime total | Empty / Ready |
| **Settings** | Quality target, schedule, work-while-using opt-in, retention, about | Ready (single state; each control observes its setting) |
| **ShareEntry** | Receives a shared video, runs InstantCompress, offers result back to share sheet | delegates to InstantCompress states |

### Screen rules

- **Hub** sorts by estimated saving descending; the top three rows should carry ~half the promised total. The "can't be shrunk" section is always rendered when non-empty — it is the app's credibility.
- **InstantCompress** never shows an unnamed spinner. Phase list renders as a checklist filling top-to-bottom. The original-fate line ("Original: kept 30 days · Change") is visible before and during the run.
- **Delete immediately** triggers a one-time-per-folder confirmation dialog, owned by the folders feature so the wording lives in exactly one place.
- **Cancel** is always enabled and always safe; the button needs no confirmation because the replace step is atomic and last (see App doc §6).

---

## 6. Navigation

Typed, sealed route graph in `navigation/`:

```kotlin
sealed interface Route {
  data object Hub : Route
  data class Compress(val ids: List<VideoId>, val source: Source) : Route
  data object Folders : Route
  data object History : Route
  data object Settings : Route
}
```

- Share-sheet and notification taps deep-link into `Compress` / `History` through the same graph — no side doors.
- Back from `Compress` never cancels the job; it detaches the UI. The job continues; a persistent notification owns re-entry.

---

## 7. Design system (`core/ui`)

### 7.1 Tokens

Single source of truth; no screen declares a color, size, or duration literal.

- **Color roles**: surface (3 elevations), text (primary/secondary/muted), accent (the headline card, links), success (savings, checkmarks), danger (delete only), warning (confirmation dialogs). Light and dark from day one — this app runs at night.
- **Type scale**: display (headline GB number), title, body, label, caption. Two weights.
- **Spacing**: 4-pt grid; component padding tokens, not ad-hoc dp.
- **Motion**: two durations (fast for state ticks, standard for navigation); progress animations must run on the render thread only — the UI must stay smooth while the device is busy encoding.

### 7.2 Component inventory

| Component | Used by | Notes |
|---|---|---|
| `HeadlineCard` | Hub | the "You can free about X GB" block |
| `VideoRow` | Hub, BigFiles | thumbnail, name, size → estimate, saving badge; selectable variant |
| `SkippedRow` | Hub, History | quieter styling by design; always shows a `SkipReason` |
| `PhaseChecklist` | InstantCompress | renders `CompressPhase` progression |
| `ProgressBarWithEta` | InstantCompress | pct + "about N min left"; ETA smoothed, never jumps backward |
| `FateLine` | InstantCompress, rows | "Original: kept 30 days ▾" with override sheet |
| `OptionCard` | Folders, dialogs | the three original-handling options; default variant carries accent border + badge |
| `PrimaryAction` / `SecondaryAction` | everywhere | exactly one primary per screen |
| `EmptyState` | all list screens | icon + one sentence + one action |

### 7.3 Copy rules (enforced in review, referenced from components)

- Sentence case; no exclamation marks in system copy.
- Estimates always prefixed "about"; savings always shown as both size and percent.
- Rejections state the reason in plain words ("too noisy to shrink"), never codes.
- The delete-immediately warning text is a single string constant, quoted by spec §8, dialog, and tests alike.

---

## 8. Frontend ↔ pipeline contract

The entire interface the frontend consumes, defined in `core/domain`:

```kotlin
interface ObserveCandidates { operator fun invoke(): Flow<CandidateSnapshot> }
interface CompressNow      { operator fun invoke(ids: List<VideoId>): Flow<CompressPhase> }
interface QueueForNight    { suspend operator fun invoke(ids: List<VideoId>) }
interface SetFolderMode    { suspend operator fun invoke(folder: FolderId, mode: OriginalFate) }
interface RestoreOriginal  { suspend operator fun invoke(id: VideoId): RestoreResult }
interface ObserveHistory   { operator fun invoke(): Flow<HistorySnapshot> }
interface ObserveSettings / UpdateSettings
```

Each has a fake implementation in `core/domain-test`. Every screen is previewable and testable against fakes with scripted timing (slow scans, mid-encode rejection, thermal pauses) — the demo build of the app runs entirely on fakes, which is also what screenshots and store listings are generated from.

---

## 9. Performance rules for the UI itself

- Lists are lazy, keyed by `VideoId`; thumbnails load through a bounded loader that pauses while the pipeline is encoding (the decoder is busy — don't fight it for hardware).
- The Hub renders from the last database snapshot instantly on open; the scan updates it incrementally. Cold open to first meaningful frame: target < 400 ms.
- Progress updates are throttled to 2 Hz before reaching the UI — encoding emits far faster than a human can read.
- No work on the main thread beyond composition. All formatting (sizes, ETAs) is precomputed into the UiState.

---

## 10. Accessibility and localisation

- Every `VideoRow` exposes a single merged content description ("Beach clip, 380 megabytes, shrinks to about 165 megabytes").
- Progress phases are announced on change via live regions.
- All sizes/percentages formatted through locale-aware formatters; strings externalised from day one; no text baked into images.
- Touch targets ≥ 48 dp; the "can't be shrunk" section, though visually quiet, keeps full-size targets.

---

## 11. Testing strategy

| Layer | Test | Runs on |
|---|---|---|
| Components | Compose UI tests + screenshot tests (light/dark, font scales) | JVM/CI |
| ViewModels | plain unit tests against fake use cases | JVM |
| Screens | state-driven: pump each `UiState` variant, assert render | JVM |
| Flows | end-to-end over the fake pipeline (scan → select → compress → history) | JVM |
| Device | one smoke test per release: real scan, one real compress, restore | device farm |

The rule that makes this cheap: because ViewModels are platform-free and every dependency has a fake, ~95% of frontend tests run without an emulator.
