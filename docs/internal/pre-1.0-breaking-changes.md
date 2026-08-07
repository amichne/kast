# Pre-1.0 Breaking Changes

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
