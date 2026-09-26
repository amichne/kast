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
- [`workspace/`](workspace/AGENTS.md) - live workspace admission, existing imported-model capture, and read epochs.
- [`symbol/`](symbol/AGENTS.md), [`source/`](source/AGENTS.md), [`relation/`](relation/AGENTS.md), [`traversal/`](traversal/AGENTS.md), [`query/`](query/AGENTS.md) - semantic read domains.
- [`evidence/`](evidence/AGENTS.md) - durable live change plans, receipts and mutation recovery; [`topology/`](topology/AGENTS.md) retains the buildable graph implementation for upcoming work.
- [`diagnostic/`](diagnostic/AGENTS.md) and [`change/`](change/AGENTS.md) - compiler diagnostics and proof-carrying mutation workflows.
- [`runtime/`](runtime/AGENTS.md) - existing-IDE plugin composition, operation dispatch, and bounded diagnostics.
- [`indexer/`](indexer/AGENTS.md) - retirement record for the former isolated semantic sidecar.
- [`app-server/`](app-server/AGENTS.md) - persistent coordinator, host adapters, workspace lanes, and Codex protocol integration.
- [`cli/`](cli/AGENTS.md) - command graph, configuration, projections, and executable bootstrap.
- [`copilot/`](copilot/extension.mjs) and [`pi/`](pi/extension.ts) - thin agent extensions over the shared tool RPC.
- [`distribution/`](distribution/AGENTS.md) and [`packaging/`](packaging/AGENTS.md) - installation contracts, managed artifacts, release assembly, and acceptance harnesses.
- [`build-logic/`](build-logic/AGENTS.md) - Gradle conventions and architecture enforcement.
- [`docs/`](docs/AGENTS.md) - public Mintlify documentation and visual evidence helpers.

## Entry Points

- Build and tests: `./gradlew build`.
- Release assembly: `./gradlew assembleRelease`.
- Local installation: `source "$(./packaging/install-checkout.sh session --idea-home "/Applications/IntelliJ IDEA.app")"` or `./packaging/install-checkout.sh persistent --idea-home "/Applications/IntelliJ IDEA.app"`.
- CLI main: `cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt`.
- Semantic host: `runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt`.
- Codex/App Server main: `app-server/src/main/kotlin/io/github/amichne/kast/appserver/KastCodexMain.kt`.
- Codex MCP main: `cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpMain.kt`.
- Harness-neutral tool RPC main: `cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt`.

## Navigation Hints

- For repository-wide orientation, start with `knowledge/index.md`, choose one concept, and follow its `code_sources` only when source detail is required.
- For domain behavior, start in the matching `*/contract`, then read `*/service`, and open `*/intellij` only for platform effects.
- For an external request, trace `protocol/registry` -> `protocol/wire` -> `runtime/hosted` -> the owning domain service.
- For startup or connectivity, start with `README.md`, then `cli`, `app-server`, `runtime/hosted`, and `workspace/intellij-read` in that order.
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
10. Preserve the signal of verification.
    A check must distinguish violations of a named invariant from changes that preserve it. False positives and false negatives both erode trust and train people to ignore failures. Prefer structured or behavioral evidence. Require exact text, hashes, ordering, names, or inventories only when that exact representation is the contract. For each guard, demonstrate rejection of a meaningful violation and acceptance of a relevant contract-preserving change. Repair noisy checks; do not silence them by regenerating baselines or broadening exceptions.

## Testing

- Start with one named behavior and the smallest execution boundary that can prove it. Virtualize only its external dependencies; run the real production rule with existing domain types, fixtures and test runners. This is the default across the repository.
- Declare the case's minimum starting facts, permitted effects and independent expected result. Keep pure parsing, resolution and policy checks in the owning contract/service tests with explicit values and no filesystem, process, socket or clock dependency. Build and runner provisioning are separate from this behavior budget.
- Enlarge the boundary only for an assertion that needs it: a private filesystem for path/lock/identity facts, a real child for process-adapter binding, compiler/platform fixtures for semantic authority, and explicitly authorized disposable native composition for OS/IDE integration. Scripted observations do not establish native behavior.
- Inject external observations at the narrowest effect boundary. Production code must own decisions, provenance, finite failure mapping and effect order. Doubles must not manufacture domain success, replace ownership proof or silently delegate to real services. Require scripted executors explicitly and reject unexpected or excess calls and unconsumed expectations; fixture violations must remain failures even if the product catches their exceptions.
- Keep mutable state case-owned. Pass environment/configuration explicitly, allocate only necessary files under an owned temporary root, and clean up only owned resources. Trigger adverse transitions through existing callbacks or controlled observations; use clocks/schedulers only when the behavior consumes time, never sleeps or races to synthesize a timeout result.
- Assert preserved identities, qualifications, typed failures and protected state on rejection. Allow legitimate observation/transition records. Derive expected results independently of the implementation under test and distinguish expected product rejection from fixture failure.
- Retain tests that already meet their dependency budget. Split coupled scenarios and move outcome-only assertions downward when touching them; retain distinct adapter/composition coverage. Prefer local helpers over shared fixtures until actual consumers need the same ownership rule. Do not introduce product test flags or a laboratory framework to exercise a dependency seam.
- Run the focused selector first, then affected files/consumers and required contract, architecture and documentation guards. Routine behavior checks must not depend on product assembly or native acceptance unless their assertion requires that boundary. Report exact commands, evidence level, skips and unverified claims; record unrelated findings without expanding the change.
- Use the [development testing guide](docs/development.md#test-one-behavior-at-a-time) for boundary selection and examples. Nested guidance may add domain-specific requirements while preserving this default and existing unique coverage.

## Search Planning

- Apply known, cheap constraints that materially reduce the search space before enumeration, candidate collection, capacity accounting, or compiler refinement. This ordering is a correctness and resource-use requirement.
- Select the smallest authoritative index and declaration families from exact names, file/directory scope, source ownership, source-set names, and retained kind constraints. Preserve those constraints through downstream operations.
- Delay constraints requiring stronger evidence only until that evidence is available. Keep package PSI outside native index callbacks; keep compiler refinement after cheap eligibility checks. An excluded container may still contain eligible descendants.
- Prove the ordering with excluded-input tests: unrelated files, names, or kinds must not exhaust the eligible result capacity. A bounded partial result must retain its qualifications.

## JSON Contracts

- Serialize types; never hand-assemble fixed contracts. Model fixed JSON requests, responses, notifications, diagnostics, persisted records, qualification witnesses, and valid test fixtures with typed DTOs and the serialization library. In Kotlin, use `@Serializable` data classes, sealed variants, and enums. Do not construct these shapes with JSON builders, maps, string interpolation, or `Any`.
- Decode at the boundary, then refine into domain types. Preserve admitted identities, outcome variants, qualifications, and finite failure codes through every projection. A known failure must never become a generic `UNKNOWN`, `UNCLASSIFIED`, missing field, or success-shaped result.
- Encode required discriminators and fixed fields explicitly, including default-valued fields. Treat absent, null, empty, and default values as distinct wherever the external contract distinguishes them.
- Keep `JsonElement` only for a contract-defined dynamic or opaque field. Document that boundary and validate the surrounding DTO. Deliberately malformed or incompatible JSON is allowed in negative tests that specifically prove its rejection.
- Verify actual encoded output against an independent expected shape or authoritative schema. Cover each closed outcome variant, required defaults/discriminators, and rejection of unknown values. A round trip through the same serializer alone is not contract proof.
- When changing an existing manual JSON boundary, migrate the affected shape and its callers in the same change. Do not extend manual construction or erase a typed failure to make a test pass.
- Run `./gradlew verifyJsonContracts`; `check` and `productBuildGate` require it. Its [syntax guard and exact baseline](config/json-contracts/README.md) reject new, changed, duplicated, and stale manual JSON expressions. Do not expand legacy allowances to pass a check. This guard supplements the encoded-shape and schema tests above; it does not replace them.

## Repository Knowledge

- Start repository-wide orientation at `knowledge/index.md`, then follow its module, flow, contract, or glossary indexes before broad source reads.
- Treat knowledge pages as source-bound routing and explanation. Their `code_sources` identify authority and impact; source, schemas, generated contracts, tests, and verified Gradle architecture remain stronger evidence.
- After changing cited source, run `./gradlew knowledgeImpact`, refresh affected concepts when their claims changed, and run `./gradlew verifyKnowledgeBase`.
