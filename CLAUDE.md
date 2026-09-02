# Trim — rules for every Claude Code session

Source of truth: `docs/spec.md`, `docs/app-architecture.md`,
`docs/frontend-architecture.md`, `docs/screens.md`. Read the relevant one
before changing the code it governs. Documents beat instincts; ask the
human when documents conflict.

> Status: `docs/app-architecture.md` and `docs/frontend-architecture.md` are
> present. `docs/spec.md` and `docs/screens.md` have **not** been supplied to
> the repo yet — see `docs/DECISIONS.md`. Anything that would depend on them
> is either deferred to a later milestone or recorded as a judgment call.

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
  Nothing above ports imports a platform class. `core/*` stays pure Kotlin,
  JVM-testable.
- Every port has one fake and one contract suite in `core/ports-contract`.
  Fakes are scriptable (delays, failures). New platform capability = new
  port + fake + contract suite, not an Android import in shared code. A real
  implementation is finished when it passes the same suite as its fake.
- Sealed results for anything that can fail; exhaustive `when`, no `else`
  branches on domain sealed types.
- One hardware encode at a time; scoring of file N−1 may overlap the
  encode of file N. Never parallel encodes.

## Working style
- Tests accompany the code in the same commit. Kill-tests for anything
  touching the replace sequence.
- Run `./gradlew check` before claiming anything is done; paste the
  result.
- Record judgment calls in `docs/DECISIONS.md` (one line each).
- Do not add dependencies without asking.
- Commit per completed unit with descriptive messages; never squash away
  the history of a safety-relevant change.

## Milestones
- **M1 (done)** — platform-free core: `core/model`, `core/ports` (+ fakes),
  `core/pipeline`, `core/data`, `core/domain`, `buildLogic`. No Android,
  Compose, MediaCodec or JNI code. `./gradlew check` must pass on a machine
  with no Android SDK. Kickoff prompt, as issued: `docs/M1-PROMPT.md`.
- **M2 (in progress)** — `androidApp` + real MediaInfo/Storage/Scheduler ports, guards #1
  and #2. Done so far: `core/ports-contract` (one shared suite per port, passing against
  every fake), guard #1a (`guardNoNetworkSources`) and guard #2
  (`guardCodecFactoryOnly`), both live and wired into `check`. Blocked in the current
  container: `dl.google.com` is denied by egress policy, so the Android SDK, AGP and every
  androidx artifact are unreachable, and there is no `/dev/kvm` for an emulator.
  **Run `tools/preflight-android.sh` before starting Android work.**
  `docs/M2-STATUS.md` records what is done and where each remaining piece plugs in;
  `docs/M2-PROMPT.md` is the kickoff prompt for finishing it.
- M3 — real Codec port (Media3 / MediaCodec). Kickoff prompt: `docs/M3-PROMPT.md`.
- M4 — real Scorer (XPSNR + libvmaf) + calibration harness. Kickoff prompt:
  `docs/M4-PROMPT.md`.
- M5 — UI per `docs/frontend-architecture.md` and `docs/screens.md`. Kickoff prompt:
  `docs/M5-PROMPT.md` — **blocked**: `docs/screens.md` has never been supplied.
- M6 — release preparation: the §11 release gates, the guards on the release variant, and
  the three things the documents promise but the code has never built (the diagnostics
  export, schema migrations, licence notices). Kickoff prompt: `docs/M6-PROMPT.md`.
  Derived from §11 and §12 rather than from the original kickoff document, which stops
  at M5.

## Tooling
Claude Code plugins for this repo are declared in `.claude/settings.json`;
the `anthropics/skills` marketplace is registered there. Run
`/plugin marketplace update anthropic-agent-skills` to refresh it.
