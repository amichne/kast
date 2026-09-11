# kast

## Purpose

Kast is a Kotlin/Gradle system that gives coding agents compiler-grounded search, semantic relationships, diagnostics, and controlled source changes for one exact repository. This file routes investigation; `AGENTS.md`, source, schemas, and tests remain authoritative.

## Key Files

- `AGENTS.md` - durable engineering rules for every change.
- `README.md` - product purpose, installation, connection, development, and failure semantics.
- `settings.gradle.kts` - authoritative included-build and module inventory.
- `build.gradle.kts` - root build configuration and shared task wiring.
- `gradle/libs.versions.toml` - dependency and plugin versions.
- `install.sh` - public local and persistent installation entry point.

## Subdirectories

- [`knowledge/`](knowledge/index.md) - checked-in, source-bound architecture, flow, contract, and glossary concepts; start broad repository orientation here.
- [`kernel/`](kernel/AGENTS.md) - shared outcomes, evidence, validation, budgets, identity, and refinement types.
- [`protocol/`](protocol/AGENTS.md) - canonical operations, registry definitions, and wire projections.
- [`workspace/`](workspace/AGENTS.md) - workspace admission, lifecycle, synchronization, IntelliJ import, and read epochs.
- [`symbol/`](symbol/AGENTS.md), [`source/`](source/AGENTS.md), [`relation/`](relation/AGENTS.md), [`traversal/`](traversal/AGENTS.md), [`query/`](query/AGENTS.md) - semantic read domains.
- [`topology/`](topology/AGENTS.md) and [`evidence/`](evidence/AGENTS.md) - graph construction and durable workspace evidence.
- [`diagnostic/`](diagnostic/AGENTS.md) and [`change/`](change/AGENTS.md) - compiler diagnostics and proof-carrying mutation workflows.
- [`runtime/`](runtime/AGENTS.md) - composition, operation dispatch, server behavior, and telemetry.
- [`indexer/`](indexer/AGENTS.md) - IntelliJ-hosted semantic sidecar and transport.
- [`app-server/`](app-server/AGENTS.md) - persistent coordinator, host adapters, workspace runtimes, and Codex protocol integration.
- [`cli/`](cli/AGENTS.md) - command graph, configuration, projections, and executable bootstrap.
- [`distribution/`](distribution/AGENTS.md) and [`packaging/`](packaging/AGENTS.md) - installation contracts, managed artifacts, release assembly, and acceptance harnesses.
- [`build-logic/`](build-logic/AGENTS.md) - Gradle conventions and architecture enforcement.
- [`docs/`](docs/AGENTS.md) - public Mintlify documentation and visual evidence helpers.

## Entry Points

- Build and tests: `./gradlew build`.
- Release assembly: `./gradlew assembleSidecarRelease`.
- Local installation: `source "$(./install.sh --local session)"` or `./install.sh --local persistent`.
- CLI main: `cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt`.
- Indexer main: `indexer/src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerMain.kt`.
- Codex/App Server main: `app-server/src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt`.

## Navigation Hints

- For repository-wide orientation, start with `knowledge/index.md`, choose one concept, and follow its `code_sources` only when source detail is required.
- For domain behavior, start in the matching `*/contract`, then read `*/service`, and open `*/intellij` only for platform effects.
- For an external request, trace `protocol/registry` -> `protocol/wire` -> `runtime/composition` -> the owning domain service.
- For startup or connectivity, start with `README.md`, then `cli`, `app-server`, `indexer`, and `workspace` in that order.
- For installation failures, start with `install.sh`, `distribution`, and `packaging`; use `app-server/docs/compatibility.md` for known Codex integration gates.
- For architecture violations, start with `build-logic/src/main/kotlin/kast.architecture.gradle.kts` and `build-logic/src/main/kotlin/support/architecture`.
- For implementation details, do not stop at this map: open the cited source and its focused tests.

## Generation Contract

- Checked-in policy: root and nested `AGENTS.md` files belong in Git, including generated navigation maps. Only `OUTDATED.local.md` and runtime caches stay local. Never add agent-file exclusions.
- Preserve authored instructions and the Engineering Dictum. Refresh generated maps only when they carry the explicit generated-navigation marker.
- Hash input: sorted immediate child names plus regular-file sizes, excluding directory sizes and mtimes. A matching shallow hash is a routing hint, not proof that nested source or semantics are unchanged.
- Exclusions: VCS metadata, dependency caches, build outputs, IDE metadata, generated navigation files, staleness markers, and binary caches.
- Staleness: the advisory post-commit hook appends the nearest existing guide directory for changed source, including the repository root, to `OUTDATED.local.md`. Review every listed map against current source and truncate only after every requested refresh succeeds. Navigation-only commits do not queue another refresh.
- Tooling: see [.agents/marketplaces.md](.agents/marketplaces.md) for marketplace ownership, expected plugins, and setup checks.


# Engineering Dictum

1. Refine; never erase.
   Information must move monotonically from weaker representation to stronger representation. Once a property is proven, preserve that proof in the representation.
2. Domain meaning requires a type.
   Primitives belong at boundaries. Inside the system, every value with domain meaning must have a representation that encodes its identity and invariants.
3. Make invalid states unrepresentable.
   States, transitions, operations, and combinations that are not valid must be excluded by construction whenever the language can express that exclusion.
4. Failure is finite data.
   Expected failure must be represented by a closed, typed, exhaustive set of conditions. Do not use exceptions, nulls, sentinels, booleans, strings, or arbitrary primitives as failure protocols.
5. Keep the core pure; make effects explicit.
   Domain logic must be deterministic and side-effect-free. Mutation, I/O, time, randomness, and external state must exist only through explicit boundaries and capabilities.
6. Fail closed.
   Unknown, ambiguous, unsupported, incomplete, or unproven states are failures. Never guess, silently recover, weaken an invariant, or manufacture success.
7. Use the strongest available authority.
   Prefer compiler proof over schema proof, schema proof over structured semantic evidence, semantic evidence over runtime observation, runtime observation over text, and text over heuristic inference. Never present weaker evidence as stronger evidence.
8. Reduce the space in which error can exist.
   Prefer fewer states, fewer transitions, fewer representations, fewer execution paths, and fewer abstractions. Completion requires mechanical evidence that the intended invariant holds.
9. Instrument as you investigate.
   When diagnosing an opaque failure requires source-level investigation, progressively make that boundary observable in the same change. Add bounded, structured, typed stage and outcome evidence at the narrowest effect boundary, and test both success and failure signals. Temporary probes may guide diagnosis, but completion replaces them with durable instrumentation. Never record secrets, source payloads, or unbounded data.

## Repository Knowledge

- Start repository-wide orientation at `knowledge/index.md`, then follow its module, flow, contract, or glossary indexes before broad source reads.
- Treat knowledge pages as source-bound routing and explanation. Their `code_sources` identify authority and impact; source, schemas, generated contracts, tests, and verified Gradle architecture remain stronger evidence.
- After changing cited source, run `./gradlew knowledgeImpact`, refresh affected concepts when their claims changed, and run `./gradlew verifyKnowledgeBase`.
