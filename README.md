# Trim

An Android video compressor that shrinks the videos already on your phone,
without a network connection and without ever losing a file.

This repository is being built milestone by milestone. **Milestone 1 is the
platform-free core**: the entire decision-making pipeline as pure Kotlin,
running and tested on the JVM against fake platform ports, with zero Android
dependencies.

```
./gradlew check      # 130 tests, passes on a machine with no Android SDK
```

## Layout

| Path | What |
|---|---|
| `docs/` | source of truth — architecture documents and the decision log |
| `shared/core/model` | entities and the honest types (`EstimateRange`, `SkipReason`, …) |
| `shared/core/ports` | the seven platform ports |
| `shared/core/ports-fake` | one scriptable fake per port |
| `shared/core/ports-contract` | one shared contract suite per port, run against fake and real alike |
| `shared/core/pipeline` | the pipeline stages and the job runner |
| `shared/core/data` | SQLDelight schema, DAOs, startup reconciler |
| `shared/core/domain` | the frontend↔pipeline contract |
| `buildLogic` | convention plugins and the build-enforced invariants (§8) |

`androidApp/`, `feature/*`, `core/ui` and `native/` arrive in later milestones —
see `CLAUDE.md`.

## What Milestone 1 proves

- **The search is correct, not merely plausible.** Its guarantee is a property test over
  400 generated monotone score curves: the cold search returns the same setting an
  exhaustive scan would. The predictor path is held to a safety property instead, because
  it trades optimality for probe count on purpose.
- **The Replacer cannot lose a file.** Its six-step commit is killed at each of the six
  steps, and each kill asserts that user storage is restored byte for byte, that the
  rollback was clean, and — checked at every operation — that the original existed at every
  moment of the sequence.
- **No failure mode is invisible.** Seventeen injections across the error taxonomy of
  app-architecture §10 run through the assembled runner; every one exits with an explained
  outcome, ends on a terminal phase, keeps the original, and leaks no temp file.
- **The guard is real.** `guardStorageWrites` is demonstrated failing on a deliberate
  violation and failing when it has nothing to scan — see `docs/GUARDS.md`.
- **The whole thing works end to end.** A seeded library of fifteen videos of mixed kinds
  scans, queues, runs, and lands on an expected-outcome table compared exactly.

## Reading order

1. `CLAUDE.md` — the invariants, and the rules for working in this repo.
2. `docs/app-architecture.md` — layers, modules, pipeline, storage safety.
3. `docs/frontend-architecture.md` — the UI layer and the honest types.
4. `docs/GUARDS.md` — the build-enforced invariants, and the evidence they are alive.
5. `docs/DECISIONS.md` — every judgment call made where the documents were silent.

## Where the build is now

Milestone 1 is complete. Milestone 2 is partly done: the contract suites and two of the
three remaining build guards are live, and the `androidApp` module is blocked on an
environment with access to the Android SDK. `docs/M2-STATUS.md` is the handoff;
`tools/preflight-android.sh` says in one command whether a machine can build it.

## Missing documents

`docs/spec.md` and `docs/screens.md` are named as sources of truth by `CLAUDE.md` but have
not been supplied to the repo. Nothing in Milestones 1–3 depended on them; everywhere a
value would normally have come from the spec, a default was chosen and recorded in
`docs/DECISIONS.md` under D0 and D3.

They become blocking later. Milestone 4's calibration harness is specified in `spec.md`
§12.1, and enough of it can be rebuilt from `app-architecture.md` §9 and §11 that the
milestone can proceed with its choices recorded. **Milestone 5 cannot.** `screens.md` is
the only source for what each screen contains, and starting without it produces an
invented UI rather than a partial one — see `docs/M5-PROMPT.md`.
