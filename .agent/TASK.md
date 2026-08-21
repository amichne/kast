# Task Contract

## Goal

A semantically identical workspace observation produces the same strongly typed, reproducible semantic identity and atomically preserves the current generation and read lease, while a real semantic change advances the generation exactly once.

## Allowed Writes

- `.agent/TASK.md`
- `.agent-turn/kotlin-agentic-correctness/`
- `workspace/contract/src/`
- `workspace/service/src/`
- `workspace/intellij/src/`
- `evidence/contract/src/`
- `evidence/sqlite/src/`
- `runtime/composition/src/`

No other paths may be modified.

## Allowed Reads

- `AGENTS.md`
- `.agent/TASK.md`
- `workspace/`
- `evidence/`
- `runtime/`
- `protocol/`
- `kernel/`
- `packaging/`
- `integration-tests/`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/`
- `gradlew`
- `.github/`
- Git history and repository state.
- The installed Kast command, its managed installation, and official GitHub release metadata.

## Non-Goals

- Implementing or changing member extension function or property resolution.
- Implementing or changing request chaining or pipeline semantics.
- Implementing or changing call-graph resolution.
- Rebinding selectors or read leases across a real semantic mutation.
- Redesigning event-source federation beyond changes strictly required for semantic identity stability.
- Publishing the findings report to an external gist or service.
- Refactoring unrelated code.
- Generalizing the implementation.
- Fixing unrelated failures.
- Adding optional improvements.

## Red Proof

Command:

```shell
./gradlew :evidence:sqlite:test --tests '*SqliteWorkspaceGenerationPublicationTest'
```

Expected failure:

The added identical-publication test shows that committing the same canonical semantic state a second time allocates a new generation instead of returning an atomic unchanged result with the current generation.

## Green Proof

Command:

```shell
./gradlew :workspace:contract:test :workspace:service:test :workspace:intellij:test :evidence:contract:test :evidence:sqlite:test :runtime:composition:test :runtime:server:test verifyKastArchitecture verifyKastModuleGraph verifyNoLegacyArchitecture installedProductTest
```

## Done When

- Canonically equivalent semantic inputs produce the same strongly typed identity regardless of capture order, no-op Gradle import metadata, or process restart.
- Identical publication returns a typed unchanged outcome inside the SQLite transaction and preserves the current generation and read lease.
- A real semantic input change returns a typed advanced outcome and increments the generation exactly once.
- Local stress evidence covers restart, no-op refresh, real semantic change, and reuse of a generation-bound read token.
- Extension/property, request-chaining, and call-graph observations are documented without implementation changes.
- The Green Proof passes.
- No files outside Allowed Writes changed.
- No Non-Goal work was performed.

## Execution State

- The task plan was confirmed with semantic identity stability as the exclusive implementation scope.
- The installed command is Kast `0.26.0`, which GitHub reports as the latest non-prerelease release, so the requested local upgrade is already satisfied.
- RED proved that a second identical SQLite commit persisted generation `2` for the same identity.
- SQLite now compares typed identity and graph evidence inside the transaction and returns `Unchanged` without allocating a new durable generation.
- The canonical publication contract now distinguishes `Advanced`, `Unchanged`, and `Rejected`; an unchanged mutation result remains a typed `GENERATION_NOT_NEWER` rejection.
- The IntelliJ capture now consumes import timestamps only as model-completeness evidence and emits one versioned `WorkspaceStateIdentity` from typed, order-independent semantic inputs and exact classpath URLs.
- Reconciliation now re-hashes current source content under the detached Gradle topology; semantic request scopes reuse the last admitted typed model instead of triggering physical identity reads.
- Focused proofs retain generation `1` across an installed-runtime reconstruction and advance to generation `2` for a changed semantic identity.
- The owning-module and direct-consumer test ring passes after a clean build; the final full `test`, module-graph, no-legacy, and repository-shape checks also pass.
- The first clean CI run exposed the previously masked static-snapshot defect as `resulting-generation-unavailable`; a filesystem-backed regression now proves that a real source edit changes the refreshed typed identity.
- The corrected clean CI run passes all checks, including the live installed-product mutation and verification flow.
- Repository-wide local completion is blocked by pre-existing verification infrastructure: `verifyKastArchitecture` describes retired modules as active and the current `runtime:composition` module as merely planned, while `installedProductTest` cannot establish the runtime endpoint on this machine despite passing in clean CI.
- `session-ses_fecf.md` and `tmp.md` are pre-existing untracked user files and must remain untouched.

## Out-of-Scope Findings

- The architecture policy model is stale relative to the 32-project topology in `settings.gradle.kts`; repairing it is unrelated to semantic identity stability.
- Both the staged product and installed Kast `0.26.0` report `endpoint-unavailable` before a live semantic request can run. Another user-owned Kast indexer for a separate worktree is active and was not interrupted.
