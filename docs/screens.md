# Trim — screens

*The revised §10 of the product spec. Every screen, every state it can be in, and the words
it uses. Companion to `frontend-architecture.md`, which describes how these are built;
this document describes what they contain.*

> ## Status: reconstruction, not the original
>
> **This document was written by Claude, not by the product owner.** The original
> `screens.md` was never supplied (`docs/DECISIONS.md` D0.1). This is a reconstruction from
> `frontend-architecture.md` §5 (which names each screen and its states), the model types
> already built, and the user-facing strings already coded and tested.
>
> The *structure* is derived: the screen list, the state lists and the rules in
> `frontend-architecture.md` §5 are not invented. **Nearly all of the copy is invented**,
> and copy is most of what a screens document is for. Read this as a well-informed proposal
> for the words, not as the words.
>
> Strings already committed to in `shared/core/model` are marked **‹coded›**. Everything
> else is a draft. Milestone 5 should not begin until a product owner has been over it —
> `docs/M5-PROMPT.md` says so.

---

## Copy rules (from `frontend-architecture.md` §7.3)

These govern every string below and every string added later.

- Sentence case. No exclamation marks in system copy, ever.
- Estimates always prefixed "about". Savings always shown as **both** size and percent.
- Rejections state the reason in plain words, never a code.
- The delete-immediately warning is one string constant, defined in `spec.md` §8.2.

Numbers are formatted locale-aware and precomputed into the UI state; no screen formats
anything itself.

---

## 1. Hub

The home screen. Answers one question — how much space can I get back — and gives two ways
to act on it.

### States

**Scanning.** First run, or a rescan with no prior snapshot.

> Looking through your videos…

A progress line naming the folder currently being read. No spinner without that line. If a
previous snapshot exists, the Hub renders it immediately and updates in place instead of
showing this state.

**Ready.** The main state.

- Headline card: **"You can free about 12.4 GB"** — the estimate is a range and the word
  "about" is not optional.
- Below it: **"from 23 videos"**, and a secondary line naming the largest single saving,
  e.g. "the biggest is Beach day, about 380 MB".
- Primary action: **Compress all tonight**
- Secondary action: **Compress selected now** (enabled only with a selection)
- The shrinkable list, sorted by estimated saving descending. The top three rows should
  carry roughly half the promised total; if they do not, the estimate model is wrong and
  the Hub will feel like it is padding.
- The "can't be shrunk" section, always rendered when non-empty:
  > **Can't be shrunk — 6 videos**
  > These are already efficient or would lose quality.

**No folder access.** No grants yet, or all grants revoked.

> **Trim needs to see your videos**
> Choose the folders Trim may look in. It only ever reads the folders you pick.

Action: **Choose folders**. If the grant cannot be requested (device policy), the action is
replaced by an explanation rather than a disabled button.

**Nothing found.** Scanned successfully, nothing worth doing.

> **Nothing to shrink right now**
> Trim looked through 41 videos (18.2 GB) and they're all as small as they should be.

If there is a non-empty "can't be shrunk" list, it is shown beneath — this state is not an
empty screen, it is a result.

### Row content

Each video row: thumbnail, name, **"380 MB → about 165 MB"**, and a saving badge
**"−56%"**. Selectable variant adds a checkbox and a running total in the app bar:
**"3 selected · about 1.1 GB"**.

Skipped rows are quieter: name, size, and the reason **‹coded›** — "already efficiently
encoded", "too noisy to shrink", "HDR video is left untouched", "has extra tracks that
would be lost", "too small to be worth shrinking", "not enough quality headroom to shrink
safely", "can't be shrunk without visible loss". Full-size touch targets despite the quiet
styling.

---

## 2. InstantCompress

One file or a batch, happening now. The screen that has to be honest under pressure.

### The phase checklist

Fills top to bottom. Never an unnamed spinner.

| Phase | Line |
|---|---|
| Checking | "Checking whether this can be shrunk" |
| Finding setting | "Finding the right setting — 3 tries" |
| Encoding | "Making it smaller" + progress bar + "about 4 min left" |
| Verifying | "Checking the result looks right" |
| Done | "Done — saved 215 MB (56%)" |
| Rejected | the reason, in the same words the Hub uses **‹coded›** |

The ETA is smoothed and never jumps backward. Progress reaches the UI at 2 Hz regardless of
how fast the encoder emits.

### Paused states

A pause is a phase, not a gap. Each shows its reason **‹coded›**:

- "paused to let your phone cool down"
- "paused — another app needed the video encoder"
- "waiting until you're charging"
- "waiting until you're not using your phone"

Each pause line adds a plain-language second line, e.g. "This will carry on by itself."

### The fate line

Visible **before and during** the run, not only at the end:

> Original: kept 30 days · **Change**

"Change" opens the folder's original-handling sheet. The three options **‹coded›** render as
"kept 30 days", "moved to SD card", "deleted".

### Cancel

Always enabled, no confirmation. The replace step is atomic and last, so cancelling is
always safe and the UI must not imply otherwise by asking twice.

### Batch

An ordered list of per-file checklists, the current one expanded. A batch header shows
**"4 of 11 · saved 820 MB so far"**. Leaving the screen detaches the UI; the job continues
and a notification owns re-entry.

---

## 3. BigFiles

The full list behind the Hub. Inherits the Hub's states plus filter state.

- Filters: all / shrinkable / can't be shrunk; by folder; by size.
- Multi-select with a running total, same as the Hub.
- Sort: saving (default), size, newest, oldest.
- Empty-with-filter is its own state: **"No videos match these filters"** with **Clear
  filters**, distinct from "nothing found".

---

## 4. Folders

Grants and per-folder policy.

### States

**Empty.**

> **No folders yet**
> Trim only looks where you tell it to.

Action: **Add a folder**

**Ready.** One card per granted folder: name, video count and total size, the current
original-handling option, and an include-in-nightly toggle. A **Remove access** action per
folder.

**Grant in progress.** The system picker is up; the screen shows a quiet placeholder rather
than a spinner, because the user is looking at the system UI, not at this.

### Original-handling options

Three `OptionCard`s. The default carries an accent border and a **Default** badge.

| Option | Description |
|---|---|
| **Keep originals for 30 days** *(default)* | "You can put any video back for 30 days. They don't take up space in your gallery." |
| **Move originals to <volume>** | "Originals go to your SD card. Nothing is deleted." |
| **Delete originals immediately** | "Frees the most space. Nothing to restore." |

Choosing the third opens the per-folder confirmation dialog defined in `spec.md` §8.2,
once per folder. The wording lives in exactly one place and this feature owns it — including
the copy used by the Settings screen's default, because the delete warning belongs to
whoever owns the deletion, not to whoever happens to display it.

The dialog names the folder, states that the policy applies from now on, and offers **Keep
originals** with default focus alongside **Delete originals**. Neither action is called
"Cancel". It contains no reassurance about how carefully Trim verifies a copy: that is true,
and it is not what the user is being asked to accept.

---

## 5. History

What Trim has done, and how to undo it.

### States

**Empty.**

> **Nothing yet**
> When Trim shrinks a video, it'll show up here.

**Ready.**

- Lifetime headline: **"You've freed 47.3 GB"**
- Completed list, newest first: name, **"380 MB → 165 MB · saved 56%"**, when, and the
  original's fate **‹coded›** with a **Restore** action while one is available.
- Restore-window line: "You can put this back for 12 more days."
- The skipped list beneath, same quiet styling as the Hub's, holding both expected skips
  and file-level failures **‹coded›** — "the video encoder couldn't handle this file",
  "couldn't read or write the file", "not enough free space to work with", "the file
  changed while it was being shrunk", "couldn't finish safely, so nothing was changed".

### Restore outcomes

Success: **"Beach day restored"**.

Refusals show their reason **‹coded›** and offer nothing false:

- "the original is no longer available to restore"
- "the original couldn't be found"
- "the smaller version has been changed since Trim made it" — plus a second line: "Trim
  won't undo changes another app made."

---

## 6. Settings

A single state; each control observes its own setting.

**Quality** — the three options from `spec.md` §5, as radio rows with their descriptions.

**When Trim runs**
- Run overnight while charging *(on)*
- Wait until fully charged *(off)*
- Stop before my next alarm *(on)*
- Nightly limit *(off)* — when on, a size picker
- Work while I'm using my phone *(off)* — "Trim usually waits until you're not using your
  phone. This lets it work anyway. It may get warm."

**Originals** — the default for newly added folders; per-folder settings live in Folders.
Setting this default to "delete immediately" opens the second dialog in `spec.md` §8.2 —
the one about folders that do not exist yet, which unlike the per-folder dialog does say the
choice can still be changed per folder, because at this point nothing has been decided about
any actual folder.

**About**
- Version
- **Privacy**: "Trim has no internet access. Nothing you have ever leaves your phone."
- **Open-source licences**
- **Save a diagnostics file** — "A file describing what Trim has skipped and why. It
  includes the names of your videos. Nothing is sent anywhere; you choose where to save
  it."

---

## 7. ShareEntry

A video arrives from another app's share sheet.

Delegates entirely to InstantCompress's states, with two differences: the fate line defaults
to the sharing folder's setting if the file is in a granted folder and to "kept 30 days"
otherwise, and on completion the screen offers **Share the smaller one** back to the share
sheet alongside **Done**.

If the shared item is not a video, or is a video Trim would skip, it says so immediately
using the same reason strings rather than starting and then failing.

---

## Accessibility

From `frontend-architecture.md` §10, restated because it is content, not styling:

- Every video row exposes one merged content description: *"Beach day, 380 megabytes,
  shrinks to about 165 megabytes, saving 56 percent."*
- Phase changes are announced through a live region as they happen.
- Skipped rows announce the reason, not just the name.
- Touch targets ≥ 48 dp everywhere, including the deliberately quiet "can't be shrunk"
  section.
- All sizes and percentages through locale-aware formatters; no text baked into images.

---

## Appendix — what this reconstruction invented

Everything not marked **‹coded›**. That is nearly all the copy, so rather than list it line
by line, here is what to review first, in order of consequence:

1. **The headline card's phrasing** (§1). It is the first thing anyone sees and it sets
   whether the app reads as confident or as hedging. "You can free about 12.4 GB" is one
   choice among many.
2. **The three original-handling option descriptions** (§4). These are how a user decides
   whether to let an app delete their videos. The delete-immediately warning itself has
   been through one deliberate rewrite — see `spec.md` §8.2, which now carries the three
   rules the wording has to satisfy as well as the wording.
3. **The diagnostics wording** (§6). It tells the user their video names are in the file.
   Getting this wrong is a privacy failure even though nothing is transmitted.
4. **The pause second-lines** (§2). They are the difference between a paused job reading as
   working or as broken.
5. **Every empty state.** Empty states are where an app either explains itself or looks
   unfinished, and all four here are invented.

The screen list, the state lists per screen, the sort orders, the always-render rule for
the "can't be shrunk" section, and the no-confirmation rule for Cancel are all derived from
`frontend-architecture.md` §5 and are not in question.
