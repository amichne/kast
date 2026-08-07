# Pre-1.0 Breaking Changes

## 2026-08-07 — Path-keyed source transition requests

- Removal: the unkeyed `WorkspaceTransitionRequester.reconcile(WorkspaceSignal)` operation and the assumption that every focused source request must invalidate an active publication cycle.
- Replacement: `WorkspaceTransitionRequest`, exact canonical path-to-content-hash or path-to-tombstone claims, and the closed `Join`, `Enqueue`, or `Rejected` route.
- Reason: concurrent refresh and diagnostics requests for the same unchanged files must share one safe publication cycle, while changed, disjoint, ambiguous, and unkeyed work must still fail closed into the pending overlay/tombstone lane.
- Paths: `indexer/src/main/kotlin/io/github/amichne/kast/idea/transition`, `indexer/src/main/kotlin/io/github/amichne/kast/idea/runtime/service`, `indexer/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic`, and focused tests.
- Proof: `WorkspaceTransitionFreshnessTest`, `WorkspaceTransitionCoordinatorTest`, `WorkspaceTransitionIngressTest`, `KastSemanticAdmissionRefreshTest`, the complete indexer suite, exact changed-Kotlin compiler analysis, full Gradle checks, and the repository-shape gate.
- Introduced by: PR #566 fast-follow.
- Compatibility: none. Internal callers must submit a typed transition request; no signal-only reconciliation adapter remains.

## 2026-08-07 — Flat workspace index and SQLite publication

- Removal: hierarchical Git/local workspace directories, local workspace registries, per-workspace `semantic-generations` directories, immutable generation copies, `current.json` pointers, restart copy-back, and pointer-durability result variants.
- Replacement: `workspaces/<sha256(canonical-workspace-path)>/cache/source-index.db`, with the verified workspace revision stored in the singleton `workspace_publication` row and committed atomically with reconciled facts. Git worktrees retain `repository-overlay.json`, tombstones, and an absolute shared repository-base database under `repositories/<sha256(canonical-common-dir)>`.
- Reason: one workspace must own one source index; SQLite already supplies the atomic visibility and crash-recovery boundary, so filesystem generations duplicated storage and authority.
- Paths: `analysis-api/src/main`, `index-store/src/main`, `indexer/src/main`, `cli-rs/src`, their focused tests, and `scripts/release/benchmark-real-repositories.sh`.
- Proof: flat-routing fixtures; `WorkspaceGenerationStoreTest`; `WorkspaceWriteTransactionTest`; `RepositorySnapshotIntegrationTest`; Rust `published_workspace` tests; full Gradle, Cargo, formatting, lint, and repository-shape gates.
- Introduced by: PR #564.
- Compatibility: none. Existing workspace state is neither moved nor read; schema 15 regenerates it at the new canonical path.

## 2026-08-07 — Proof-carrying runtime progress and Git reads

- Removal: arbitrary `ReadOnlyGitCommand.processBuilder` argument lists, Boolean readiness summaries and wait results, nullable progress timestamps, raw retry counters, primitive Gradle inventory and settlement-policy fields, and exception-only progress-wait outcomes.
- Replacement: fixed read-only Git command factories; closed readiness, consistency, deadline, lifecycle, completion, and wait outcomes; typed progress timing and work; typed Gradle inventory and policy bounds; and finite failures adapted to exceptions only at Java, IntelliJ, or serialization boundaries.
- Reason: every fact established by changed Kotlin must survive as a more constrained derivation instead of being discarded into primitives, nullability, call order, or arbitrary exceptions.
- Paths: `AGENTS.md`, `analysis-api/src/main`, `indexer/src/main`, and their focused tests.
- Proof: `ReadOnlyGitCommandTest`, `RuntimeStatusResponseTest`, `ProgressAwareFutureAwaiterTest`, `GradleModelSettlementAwaiterTest`, `KastIdeaProjectIndexingRuntimeTest`, the full Gradle test graph, and the repository-shape gate.
- Introduced by: PR #564.
- Compatibility: none. Callers must construct and consume the typed contracts directly.

## 2026-08-07 — Source-policy-bound ignored Git evidence

- Removal: the undifferentiated `WorkspaceHasIgnoredKotlinSources` singleton and the raw nonempty-byte check that treated every ignored Kotlin path as snapshot-invalidating.
- Replacement: `IgnoredKotlinSourceAuthority`, strict NUL-delimited Git-output parsing, and a path-carrying `WorkspaceHasIgnoredKotlinSources` failure derived through `SourceIndexFilePolicy`.
- Reason: ignored files under hard-excluded build and tool directories cannot affect the source index and must not disable committed snapshot reuse; malformed output and eligible ignored sources must still fail closed.
- Paths: `indexer/src/main/kotlin/io/github/amichne/kast/idea/snapshot/CommittedGitTreeResolver.kt` and `indexer/src/test/kotlin/io/github/amichne/kast/idea/workspace/RepositorySnapshotIntegrationTest.kt`.
- Proof: `RepositorySnapshotIntegrationTest`, full Gradle checks, compiler-backed analysis of every changed production Kotlin file, and the repository-shape gate.
- Introduced by: PR #564.
- Compatibility: none. Consumers of `WorkspaceHasIgnoredKotlinSources` must inspect its canonical repository-relative path.
