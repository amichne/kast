# Pre-1.0 Breaking Changes

## 2026-08-07 — Flat workspace index and SQLite publication

- Removal: hierarchical Git/local workspace directories, local workspace registries, per-workspace `semantic-generations` directories, immutable generation copies, `current.json` pointers, restart copy-back, and pointer-durability result variants.
- Replacement: `workspaces/<sha256(canonical-workspace-path)>/cache/source-index.db`, with the verified workspace revision stored in the singleton `workspace_publication` row and committed atomically with reconciled facts. Git worktrees retain `repository-overlay.json`, tombstones, and an absolute shared repository-base database under `repositories/<sha256(canonical-common-dir)>`.
- Reason: one workspace must own one source index; SQLite already supplies the atomic visibility and crash-recovery boundary, so filesystem generations duplicated storage and authority.
- Paths: `analysis-api/src/main`, `index-store/src/main`, `indexer/src/main`, `cli-rs/src`, their focused tests, and `scripts/release/benchmark-real-repositories.sh`.
- Proof: flat-routing fixtures; `WorkspaceGenerationStoreTest`; `WorkspaceWriteTransactionTest`; `RepositorySnapshotIntegrationTest`; Rust `published_workspace` tests; full Gradle, Cargo, formatting, lint, and repository-shape gates.
- Introduced by: PR #564.
- Compatibility: none. Existing workspace state is neither moved nor read; schema 15 regenerates it at the new canonical path.
