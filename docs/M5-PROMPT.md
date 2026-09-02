# Milestone 5 kickoff prompt

Paste the block below into Claude Code, in a session that has the Android SDK.

**Hard precondition: `docs/screens.md` must be signed off by a product owner.**

It now exists — but it is a *reconstruction written by Claude*, not the original §10 screens
document, which was never supplied (`docs/DECISIONS.md` D0.1, D0.3). Its structure is
derived from `frontend-architecture.md` §5 and is sound. **Nearly all of its copy is
invented**, and copy is most of what a screens document is for.

This precondition is therefore not "the file exists". A session that opens `screens.md`,
sees a complete-looking document and builds against it will ship Claude's guesses as the
product's voice — which is exactly the outcome the original version of this precondition
existed to prevent. The file existing makes that mistake *easier*, not harder.

Before pasting this prompt, have a human read `screens.md` and at minimum settle the five
items its appendix lists as most consequential: the headline card, the three
original-handling descriptions, the diagnostics wording, the pause second-lines, and the
empty states. The same applies to `spec.md` §8.2, whose delete-immediately warning is
quoted by this milestone and is marked **[UNSIGNED]**.

**Precondition: Milestones 2–4 done.** The real variant wires to the real pipeline last,
so it needs one.

---

```
Read docs/frontend-architecture.md in full, docs/screens.md in full, docs/spec.md §8,
CLAUDE.md and docs/DECISIONS.md (D0.3, D2.1, D2.2, D2.5, D2.5b, D8.7) before writing any
code. Note that spec.md and screens.md are reconstructions rather than originals: strings
marked ‹coded› in screens.md are already committed to and tested, and everything else in it
is a draft you should expect to be corrected.
These documents are the source of truth. Where they conflict with your instincts, the
documents win. Where they conflict with each other, ask me.

You are building Milestone 5 of Trim: the user interface. Everything underneath it is
done, tested and honest. The one way to spoil that is to compute something on this side of
the line — every number on screen already exists in the pipeline, and if one does not,
that is a pipeline gap to bring to me and not a calculation to do in a ViewModel.

## The rule that governs the whole milestone

frontend-architecture §1: the UI never computes; it renders decisions already made. The
honest types exist to make the alternative impossible, and they are load-bearing:

- EstimateRange has no single-number constructor. There is no way to render a false
  precision, because there is no way to be given one.
- SkipReason and FailureReason both carry displayText. There is no bare "skipped" to
  render, so the "can't be shrunk" list cannot become a shrug.
- CompressPhase has no anonymous progress state. A spinner with no name is unbuildable.
- OriginalFate carries displayText, so no completion message can omit what happened to
  the original.

CLAUDE.md states this as an invariant: do not weaken these types to make a screen easier.
If a screen is hard to build because a type will not lie, the screen's design is the thing
to question.

## Scope — build exactly this, nothing more

1. core/ui, the design system, FIRST and on its own. Tokens before components, components
   before screens (frontend §7).

   - Colour roles, type scale, 4-pt spacing, two motion durations. Light and dark from day
     one — this app runs at night.
   - No screen declares a colour, size or duration literal. That is checkable; consider
     making it checkable, in the style of the build guards this repo already has.
   - The component inventory of §7.2: HeadlineCard, VideoRow, SkippedRow, PhaseChecklist,
     ProgressBarWithEta, FateLine, OptionCard, PrimaryAction/SecondaryAction, EmptyState.
     Build them against the real model types, not against strings.

2. core/domain-test — the fakes for the §8 contract interfaces. DECISIONS D8.7 records
   that Milestone 1 deliberately did not ship these; this is the milestone that needs
   them. They must be scriptable in the way §8 asks: slow scans, mid-encode rejection,
   thermal pauses. A demo of a happy path is not a fake, it is a screenshot.

   Note that CompressNow is declared in core/domain/Contract.kt and has no implementation
   yet. Depending on what Milestone 2 did with the worker, you may be writing it — check
   before assuming, and if you write it, it belongs in core/domain and not in a feature.

3. The screens, per docs/screens.md, each with every state it names. frontend §5 gives the
   state lists; screens.md gives the content.

   Hub · InstantCompress · BigFiles · Folders · History · Settings · ShareEntry

   Rules from §5 that are easy to lose and expensive to lose:
   - Hub sorts by estimated saving descending, and the top three rows should carry about
     half the promised total. The database query already sorts this way and the end-to-end
     test in core/domain already asserts the top-three property — do not re-sort in a
     ViewModel.
   - The "can't be shrunk" section is always rendered when non-empty. It is the app's
     credibility, and it is quiet by design, not hidden.
   - InstantCompress never shows an unnamed spinner. The phase list is a checklist filling
     top to bottom, and the original-fate line is visible before and during the run.
   - Cancel is always enabled and needs no confirmation, because the replace step is atomic
     and last. Do not add a confirmation dialog to be safe; it would be dishonest about a
     risk that does not exist.
   - The delete-immediately warning is ONE string constant, quoted by spec §8, the dialog
     and the tests alike. Its one-time-per-folder confirmation is owned by the folders
     feature so the wording lives in exactly one place.

4. navigation/ — the typed sealed route graph of §6. Share-sheet and notification taps
   deep-link through the same graph; there are no side doors. Back from Compress detaches
   the UI and never cancels the job.

5. Performance and accessibility, built in rather than retrofitted (§9, §10).

   - Lists lazy and keyed by VideoId. Thumbnails through a bounded loader that pauses while
     the pipeline is encoding — do not fight the decoder for hardware.
   - The Hub renders the last database snapshot instantly on open; the scan updates it
     incrementally. Target cold open to first meaningful frame under 400 ms, and measure it
     rather than hoping.
   - Progress throttled to 2 Hz before it reaches the UI (D6.7).
   - All formatting — sizes, percentages, ETAs — precomputed into the UiState by a
     locale-aware formatter. No work on the main thread beyond composition.
   - Every VideoRow exposes one merged content description. Phase changes announce via live
     regions. Touch targets ≥ 48 dp, including in the quiet "can't be shrunk" section.

6. Two variants. The demo build runs entirely on the fakes with scripted timing — it is
   what screenshots and store listings come from. The real variant wires to the real
   pipeline, and it is wired LAST.

## Tests

- ViewModels: plain unit tests on the JVM against fake use cases. These are the bulk, and
  they must not need an emulator — that is the payoff of ViewModels holding zero platform
  references.
- Screens: state-driven. Pump every UiState variant and assert it renders. "Every state is
  designed, including the ugly ones" (§1.4) is only true if every state is tested.
- Screenshot tests in light and dark, and at large font scales.
- Flows: end to end over the fake pipeline — scan, select, compress, history.
- One device smoke test per release: real scan, one real compress, restore.

## Non-goals — do not build these

- No pipeline logic, no file access, no codec knowledge above the domain layer. The
  frontend talks to use cases and nothing else (§2).
- No new numbers. If a screen wants a value the pipeline does not expose, that is a
  conversation with me about the domain contract.
- No loosening of the honest types. Not temporarily, not behind a flag.

## How to work

- core/ui first, complete, with its screenshot tests, before any feature module.
- Then one feature at a time: UiState and UiEvent, then the ViewModel with its JVM tests,
  then the screen with its state-driven tests. Features never depend on each other;
  cross-feature navigation goes through the route graph.
- Commit per completed unit. Record judgment calls in docs/DECISIONS.md, one line each.

## Definition of done

- Every screen in docs/screens.md exists with every state it documents, and every state is
  covered by a test that pumps it.
- The demo build runs the whole app on fakes, with scripted slow scans, a mid-encode
  rejection and a thermal pause visible in it.
- Screenshot tests pass in light and dark.
- ~95% of frontend tests run without an emulator, per §11's claim. If that number is much
  lower, something took a platform dependency it did not need.
- No screen file contains a colour, size or duration literal.
- The delete warning string appears exactly once in the source.
- The honest types are unchanged. `git diff` on core/model over this milestone should be
  empty, or every line of it should be something you brought to me first.

## Bring these to me rather than deciding them

- Anything screens.md does not cover. Inventing screen content is the one failure mode
  this milestone has, and it is invisible until someone reads the result. This applies
  doubly now that screens.md is itself partly invented — do not build on a draft string
  and then treat it as settled because it was in a file.
- Any urge to compute a number in a ViewModel.
- Any screen whose design is hard because a type will not lie.

Start by reading the documents, then show me your plan as a short ordered task list before
writing code.
```
