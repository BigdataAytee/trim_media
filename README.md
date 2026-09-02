# Trim

An Android video compressor that shrinks the videos already on your phone,
without a network connection and without ever losing a file.

This repository is being built milestone by milestone. **Milestone 1 is the
platform-free core**: the entire decision-making pipeline as pure Kotlin,
running and tested on the JVM against fake platform ports, with zero Android
dependencies.

```
./gradlew check      # passes on a machine with no Android SDK
```

## Layout

| Path | What |
|---|---|
| `docs/` | source of truth — architecture documents and the decision log |
| `shared/core/model` | entities and the honest types (`EstimateRange`, `SkipReason`, …) |
| `shared/core/ports` | the seven platform ports |
| `shared/core/ports-fake` | one scriptable fake per port |
| `shared/core/pipeline` | the pipeline stages and the job runner |
| `shared/core/data` | SQLDelight schema, DAOs, startup reconciler |
| `shared/core/domain` | the frontend↔pipeline contract |
| `buildLogic` | convention plugins and the build-enforced invariants (§8) |

`androidApp/`, `feature/*`, `core/ui` and `native/` arrive in later milestones —
see `CLAUDE.md`.

## Reading order

1. `CLAUDE.md` — the invariants, and the rules for working in this repo.
2. `docs/app-architecture.md` — layers, modules, pipeline, storage safety.
3. `docs/frontend-architecture.md` — the UI layer and the honest types.
4. `docs/DECISIONS.md` — every judgment call made where the documents were silent.
