# Trim — product specification

> ## Status: reconstruction, not the original
>
> **This document was written by Claude, not by the product owner.** The original
> `spec.md` was never supplied to this repository (`docs/DECISIONS.md` D0.1). This is a
> reconstruction assembled from `app-architecture.md`, `frontend-architecture.md`, the
> behaviour already built and tested in `shared/core/`, and the judgment calls recorded in
> `DECISIONS.md`.
>
> Most of it is *derived* — it describes decisions that are already made, already coded and
> already tested, and writing them down changes nothing. Some of it is *invented*, because
> nothing in the repository implied an answer. The invented parts are marked **[UNSIGNED]**
> inline and listed together in the appendix.
>
> Until those are reviewed, this document is a proposal. Treat an **[UNSIGNED]** line as you
> would a `DECISIONS.md` entry: a reviewable guess, not an instruction. The danger of a
> reconstructed spec is that it launders guesses into authority, and the marks exist to stop
> that.
>
> §9 and §10 are superseded by `app-architecture.md` and `screens.md` respectively, as those
> documents themselves state.

---

## §1 What Trim is

Trim makes the videos already on your phone smaller, without you having to think about it,
and without ever losing one.

It is not a video editor, a cloud backup, a file manager or a gallery. It does one thing:
it finds videos that are larger than they need to be, re-encodes them at a quality you
cannot see the difference from, and puts the smaller file back exactly where the original
was — same name, same place, same date, same position in your camera roll.

Three promises define it, and each is a checkable statement about the codebase rather than
a claim about behaviour (`app-architecture.md` §12):

- **It can't lose a file.** Every failure path leaves the original intact. This is enforced
  by the commit sequence in §6 of the architecture document and tested by killing that
  sequence at each of its six steps.
- **No internet, ever.** There is no network layer to misuse — not disabled, absent, and
  verified absent by the build.
- **It won't drain your battery.** The nightly run exists only while the phone is charging
  and idle, enforced by the operating system rather than by app logic.

## §2 Who it is for

Someone whose phone is full of video they want to keep and never watch: a 4K clip of a
birthday, three minutes of a dog, a concert they will show one person. They do not want to
delete it, do not want to upload it, and do not want to learn what a bitrate is.

The person Trim is *not* for is the one who wants control over the encode. Every setting in
this app is about consent — what may be touched, when, and what happens to originals — and
none of it is about codecs.

## §3 Scope and non-goals

**In scope**: finding compressible video on the device, deciding whether each file is worth
compressing, compressing it, verifying the result, replacing the original, and letting the
user undo that.

**Out of scope, permanently:**

- Any network feature. No accounts, no cloud, no sharing service, no update check, no
  telemetry, no crash reporting.
- Software encoding. A file the hardware cannot handle is skipped with a reason.
- Photos. Video only.
- Editing of any kind — no trimming despite the name, no filters, no rotation.
- Re-compressing a file Trim has already compressed. Generational loss is prevented
  structurally: the processed list is a hard gate, not a warning.

**Out of scope for v1, possibly later:** iOS, per-file quality overrides, a "compress
everything older than N" rule.

## §4 What gets compressed

The user never chooses files by technical criteria. Trim decides, and explains every
decision it makes.

### §4.1 The rule

A file is worth compressing if it is spending more bits per pixel per second than it needs
to. **Never a flat megabit threshold** — a flat threshold punishes 4K60 for being 4K60 and
lets a bloated 720p clip through. The bar differs by the source codec, and is *lowest* for
H.264: converting H.264 to HEVC harvests a whole codec generation before any quality is
traded, so a mildly inefficient H.264 file is still worth doing, while an AV1 file must be
genuinely wasteful before re-encoding is anything but a quality loss.

Concrete thresholds are engineering values, not product ones; they live in
`PipelineConfig` and are recorded in `DECISIONS.md` D3.1. They are expected to move when
the §12.1 corpus says they should.

### §4.2 What is never touched

Seven reasons, each shown to the user in plain words. These strings are the specification:

| Reason | Shown as | Why |
|---|---|---|
| Already efficient | "already efficiently encoded" | there is nothing to gain |
| Too noisy | "too noisy to shrink" | grain is incompressible; every bit saved would show |
| HDR | "HDR video is left untouched" | re-encoding risks the tone mapping |
| Extra tracks | "has extra tracks that would be lost" | multiple audio or subtitle tracks would be dropped |
| Too small | "too small to be worth shrinking" | the work costs more than the saving |
| No headroom | "not enough quality headroom to shrink safely" | the file cannot score above the target even against itself |
| Cannot reach target | "can't be shrunk without visible loss" | no setting clears the quality bar |

The "can't be shrunk" list is always shown when it is non-empty. It is quiet by design but
never hidden: it is the app's credibility. A user who sees Trim decline to touch things is
a user who believes it when it does.

### §4.3 Promising a saving

A file is only offered if Trim expects to save at least **15%** of it. Below that the
churn is not worth the user's storage write cycles or their attention. **[UNSIGNED]** — 15%
is a chosen figure; nothing in the architecture documents implies it.

Estimates are always ranges and always prefixed "about". There is no single-number estimate
before a probe has run, and the type system makes one unrepresentable.

## §5 Quality

Three settings, described by outcome rather than by number:

| Setting | Means | Target |
|---|---|---|
| Highest quality | "keep it visually identical" | VMAF 96 |
| Balanced *(default)* | "you won't see the difference" | VMAF 95 |
| Smallest files | "a little loss for a lot more space" | VMAF 93 |

The user sees the left two columns; the third is engineering. Every compressed file is
verified against the target before it replaces anything, and a file that misses is
discarded with the original untouched.

**[UNSIGNED]** — the three names and their descriptions are invented copy. The VMAF values
are already coded and tested.

## §6 When it runs

**Nightly, by default.** While charging and idle, enforced by the operating system: unplug
the phone and the process stops existing. There is no app logic that could drain a battery
on this path, because the app is not running to have a bug.

The user may additionally allow work while they are using the phone; it is off by default.

Optional controls:

- Wait until fully charged (off by default)
- Stop before the next alarm (**on** by default — a phone that is warm at 6am is a phone
  the user resents)
- A nightly limit on how much is processed (off by default)

Trim also duty-cycles for heat: it pauses when the device is warm and resumes when it is
not, preferring planned rest over running flat-out into throttling. Every pause is named on
screen — "paused to let your phone cool down", "paused — another app needed the video
encoder", "waiting until you're charging", "waiting until you're not using your phone".
There is no unexplained stall.

**Instant compress** is the other path: the user taps a file, or shares one to Trim, and it
is done now, on battery if necessary, because they asked.

## §7 Folders and originals

Trim only sees folders the user has granted. There is no "scan everything" and no implicit
access.

Per folder, the user chooses what happens to originals after a successful compression:

| Option | Shown as | Default |
|---|---|---|
| Keep for a while | "kept 30 days" | **yes** |
| Move to another volume | "moved to <volume>" | when a card is present |
| Delete immediately | "deleted" | no |

30 days is the default retention. **[UNSIGNED]** — derived from the example copy in
`frontend-architecture.md` ("Original: kept 30 days"), which is illustrative rather than
normative.

Each folder also has an include-in-nightly toggle, on by default for granted folders.

## §8 Deletion, undo, and the warning

### §8.1 Undo

Every compression is undoable while its original still exists. Restoring puts the original
back at its exact path and removes the smaller copy.

Restore refuses in three cases, each with its reason:

- "the original is no longer available to restore" — the retention window closed
- "the original couldn't be found" — it was removed outside Trim
- "the smaller version has been changed since Trim made it" — another app edited the
  compressed file, and putting the original back would destroy that work rather than undo
  Trim's

### §8.2 The delete-immediately warning

Choosing to delete originals immediately is the only irreversible setting in the app. It
requires a one-time confirmation per folder, and the wording is a **single string
constant** quoted by this section, by the dialog, and by the tests alike:

> **Originals will be deleted straight away**
>
> Trim will replace each video with a smaller copy and delete the original immediately.
> There will be nothing to restore. Trim checks every copy before replacing anything, but
> if you later decide you wanted the original, it will be gone.

Confirm button: **Delete originals**. Cancel button: **Keep originals for 30 days**.

**[UNSIGNED]** — this wording is invented. It is the most consequential copy in the app and
the piece most likely to be wrong; it needs a product owner and arguably a lawyer.

## §9 Architecture

**Superseded.** See `docs/app-architecture.md`, which describes itself as "a restructured,
expanded replacement for §9 of the product spec".

## §10 Screens

**Superseded.** See `docs/screens.md`.

## §11 Privacy

Trim collects nothing, sends nothing, and has nowhere to send it to.

- No network permission in any build. This is verified against the merged manifest of every
  variant and against every source file, including native sources.
- No accounts, no identifiers, no analytics, no crash reporting.
- The Play Data safety declaration reads "no data collected", and that declaration is the
  store-facing restatement of a build-enforced invariant rather than a promise.

The one diagnostic path is a **local file the user exports by hand**. It records skips,
failures, invariant breaches and calibration fallbacks. It is opt-in, it is readable by the
user before it goes anywhere, and nothing leaves the device unless the user moves it. It
contains video file names, and the user is told so before exporting.

## §12 Quality assurance

Two release gates, both on real devices:

1. **The metric-calibration corpus** — real clips with known VMAF/XPSNR pairs per device
   class (§12.1).
2. **End to end on a seeded media folder** — scan, compress, verify, replace, restore.

Plus, on every commit: the pipeline's own test suite against fake platform ports, the
shared port contract suites, and the build-enforced invariants.

### §12.1 The calibration harness

Trim searches on XPSNR because it is fast enough to binary-search, but the user's quality
target is expressed in VMAF, which is slow. The bridge between them is a calibration table,
and this harness is how that table is derived.

It is an instrumented run that:

1. takes a folder of sample clips spanning the content types §4.2 distinguishes — clean
   daylight, low-light grain, high-motion, synthetic/screen, and a wide range of source
   bitrates;
2. encodes each clip at a ladder of settings across the search bracket;
3. scores every result with **both** metrics, at search subsampling;
4. writes a CSV of `(device, clip, setting, xpsnr, vmaf)`.

The output is a per-device-class curve mapping a VMAF target to the XPSNR value that stands
in for it. Requirements:

- **No threshold is hardcoded anywhere.** The table is data, shipped per device class and
  updatable without touching the native layer.
- A device with no calibration falls back to a generic curve and **says so loudly** in
  diagnostics. Silence would mean shipping a guess that looks like a measurement.
- The CSV appends and carries a device column, so two phones' results sit in one file. It
  will be run by someone who is not its author, on hardware they have never seen.

**[UNSIGNED]** — the CSV columns, the ladder's spacing, the corpus composition and how many
clips constitute enough are all invented here. The architecture document specifies the
harness's purpose and outputs but not its shape.

## §13 Release criteria

A build ships when:

- both §12 gates pass on a device farm;
- every claim in the store listing maps to a check that runs in CI against the release
  variant;
- the merged release manifest's permission list is enumerated and justified, and contains
  no `INTERNET`;
- the minified release build passes the full instrumented suite, not just the debug build;
- database migrations are verified, and an install-over-upgrade restores an undo entry
  written by the previous version.

See `docs/M6-PROMPT.md` for the working form of this list.

---

## Appendix — what this reconstruction invented

Everything marked **[UNSIGNED]** above, gathered for review. Each is a product decision
that nothing in the repository implied, and each is currently load-bearing somewhere.

| § | Decision | Why it matters |
|---|---|---|
| §4.3 | The 15% minimum promised saving | already coded as `minimumSavingFraction`; changing it changes which files are offered |
| §5 | The names and descriptions of the three quality settings | user-facing copy on the Settings screen |
| §7 | 30 days as the default retention | derived from an illustrative line in the frontend document, not from a stated rule |
| §8.2 | The entire delete-immediately warning wording | the only irreversible setting in the app |
| §12.1 | CSV columns, ladder spacing, corpus composition | determines whether the calibration output is usable by anyone but its author |

Additionally, the section numbering here is reconstructed from external references: other
documents cite §8 for the delete warning, §9 for architecture, §10 for screens and §12.1 for
the calibration harness, and those four are honoured. The remaining numbering is a guess and
carries no authority.
