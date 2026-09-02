# Milestone 6 kickoff prompt — release preparation

Paste the block below into Claude Code, in a session that has the Android SDK and a device.

**This milestone is derived, not transcribed.** M1–M5 came from the original kickoff
document; it stops at the UI. M6 is assembled from `app-architecture.md` §11, which names
two explicit **release gates**, and §12, which says the deliberate constraints are what
make the marketing claims "checkable statements about the codebase rather than promises
about behaviour". If a real `spec.md` arrives with its own release section, that document
wins over this one.

**Precondition: Milestones 2–5 done**, and `docs/spec.md` and `docs/screens.md` present.

---

```
Read docs/app-architecture.md (§8, §10, §11, §12), docs/frontend-architecture.md (§8, §10),
CLAUDE.md, docs/GUARDS.md and docs/DECISIONS.md (D6.9, D7.2) before writing any code.
These documents are the source of truth. Where they conflict with your instincts, the
documents win. Where they conflict with each other, ask me.

You are preparing Trim for release. This is not a checklist of store chores. §12 is
explicit that the app's constraints exist so that the claims on the listing are checkable
statements about the codebase — "can't lose a file", "no internet, ever", "won't drain your
battery". Your job is to make every one of those traceable to a check that runs in CI, and
to close the three places where this repository currently promises something it has never
built.

Releasing is also the moment several things stop being reversible. A schema without a
migration, a permission nobody justified, a guard exempted "just to ship" — after the first
public build, all three are somebody's data.

## The three things promised and not built

Find these first. They are not polish; each is a promise already written down.

1. The diagnostics export does not exist. §10 says an invariant breach is recorded "for the
   opt-in export" and §12 says "diagnostics are a local file the user exports by hand".
   Four places in the code refer to that export in their comments. What actually exists is
   JobRunner.diagnostics — a MutableList<String> that dies with the process.

   Build it: durable across runs, opt-in, written to a file the user exports through SAF.
   The user must be able to read it before it goes anywhere; nothing leaves the device
   unless they move it themselves. Decide what it may contain and write that down — it will
   hold file names and failure reasons, and a user sending it to us is sending us their
   video titles.

   It needs an entry point in Settings. If Milestone 5 already added an About or
   Diagnostics row, wire to it; if not, this milestone adds one, and that is UI work — say
   so rather than smuggling it in.

2. SQLDelight's verifyMigrations is OFF. DECISIONS D6.9 records why — the schema-snapshot
   task only exists once a migration file does — and says to turn it on in the same commit
   as the first .sqm. That commit is this milestone's, and it is the highest-consequence
   item here. After the first public release, a schema change without a migration is data
   loss on upgrade, and there is no guard standing in front of it today.

   Add migration 1, turn verifyMigrations on, and add a test that opens a v1 database
   written by the current schema and migrates it.

3. There are no licence files. No LICENSE, no NOTICE, no third-party notices. libvmaf and
   XPSNR both carry terms, and by release the app links both. Add a repository LICENSE, a
   third-party notices file generated from the actual dependency set rather than hand-typed,
   and an in-app notices screen reachable from Settings.

## Scope — build exactly this, nothing more

4. The claim ledger: docs/RELEASE-CLAIMS.md. One row per claim the store listing makes,
   each naming what makes it true and the check that verifies it. Start from §12's three
   and the Play Data safety declaration:

   | Claim | What makes it true | Checked by |
   |---|---|---|
   | no internet, ever | no INTERNET in the merged manifest of any variant; no networking API in any Kotlin, C or C++ source | guardNoNetworkManifest, guardNoNetworkSources |
   | no data collected (Data safety form) | the same two guards — the declaration is the store-facing restatement of a build-enforced invariant | as above |
   | can't lose a file | the Replacer's six-step commit and reverse rollback; the storage port behaves as the sequence assumes | Replacer kill-tests, StorageContract on device, §11's end-to-end gate |
   | hardware only, never grinds your CPU | no codec obtained outside CodecFactory; no software encoder linked | guardCodecFactoryOnly |
   | won't drain your battery | WorkManager's OS-enforced charging + idle constraints; RunPolicy | RunPolicy unit tests, one real overnight run on a device |
   | a file is never processed twice | the processed list is a hard gate in the database | the end-to-end test's second-scan assertion |

   The rule: a claim with no check is either given one or removed from the listing. Bring me
   any you cannot back — do not soften the wording to make it defensible.

5. Guards on the release variant. Today they run on sources and on `check`. Guard #1b must
   check the merged manifest of EVERY variant including release, and the whole guard set
   must run in CI against the release build, minification included. Show me guardNoNetwork-
   Manifest failing on a release-variant violation specifically, not just on debug.

6. The release build itself.
   - R8 with minification and resource shrinking on, and the full instrumented suite passing
     against the minified build. SQLDelight and WorkManager both use reflection; the suite
     passing on debug proves nothing about the shipped artifact.
   - Signing through Play App Signing. No keystore in the repository, and add a check that
     one never arrives.
   - A baseline profile for the cold-open path — frontend §9 targets under 400 ms to first
     meaningful frame, and that is a number to measure on the release build.

7. Permissions audit. Enumerate every permission in the merged release manifest and justify
   each one in the ledger. Anything you cannot justify in a sentence comes out. Expect
   media read access, SAF grants, a foreground service with its type, and notifications —
   and expect no INTERNET and no ACCESS_NETWORK_STATE.

8. The two release gates §11 already names, wired so they block a release rather than being
   run from memory:
   - the metric-calibration golden-file corpus, on a device farm;
   - end to end on a seeded media folder: scan → compress → verify → replace → restore.

9. Store assets from the demo build. frontend §8 says the fakes-only demo build is what
   screenshots and store listings are generated from. Make that one command, deterministic
   and regenerable, so a screenshot can never drift from what the app does.

10. Upgrade path. Install-over-install: the startup reconciler must run on first launch
    after an upgrade, and an undo bin written by the previous version must still restore.
    Test it, do not reason about it.

## Non-goals — and this app is unusual in two of them

- NO crash reporting. No Crashlytics, no Sentry, no bug-reporting SDK. §12 forbids
  telemetry, guard #1 will fail the build, and the diagnostics export is the sanctioned
  alternative. Every release checklist you have ever seen says to add crash reporting. This
  one says the opposite, on purpose.
- NO analytics, no A/B, no remote config, no runtime feature flags. There is nowhere to
  fetch them from and nowhere to send them to.
- No new features. A release is where features stop.
- No temporary guard exemptions to get a build out. If a guard blocks the release, the
  guard is right until I say otherwise.

## Definition of done

- docs/RELEASE-CLAIMS.md exists, every row names a check, and every check runs in CI
  against the release variant.
- The diagnostics export works on a device: opt-in, written, exported through SAF, readable
  by the user first. A test writes one and reads it back.
- verifyMigrations is true, migration 1 exists, and a test migrates a v1 database.
- LICENSE, generated third-party notices, and an in-app notices screen.
- The minified, signed release build passes the full instrumented suite and both §11 gates.
- The merged release manifest's permission list is enumerated and justified, with no
  INTERNET.
- Screenshots regenerate from the demo build with one command.
- An install-over-upgrade restores an undo entry written by the previous version.
- `./gradlew jvmTest` still passes with no device attached.

## Bring these to me rather than deciding them

- Any listing claim you cannot back with a check.
- What the diagnostics export may contain. It will hold video file names; a user who sends
  it to us is sending us their library's titles, and I want to have decided that on purpose.
- Anything that wants a network permission for any reason, including a store requirement.
  That is a conversation about whether the app's central claim survives, not a config change.
- Any guard you want to exempt to ship.

Start by reading the documents, then show me your plan as a short ordered task list before
writing code. Begin with the three unbuilt promises — they are the ones most likely to be
mistaken for done.
```
