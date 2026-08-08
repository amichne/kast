---
type: How-to Guide
title: How to Maintain Repository Intelligence
description: Change and verify Kast's compiler-backed repository query path.
tags: [maintenance, repository-intelligence, validation, recovery]
code_sources:
  - path: cli-rs/src/semantics/repository_intelligence.rs
  - path: indexer/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/semantic/SemanticGraphWriter.kt
  - path: .github/scripts/test-release-indexing-benchmark-contract.sh
---

# How to Maintain Repository Intelligence

Use this guide when changing repository questions, compiler graph production,
SQLite persistence, query algorithms, or projection. Start with the narrowest
authority that owns the behavior, prove one case, then broaden verification
only when a shared contract moved.

## Start from the exact worktree

Read the nearest `AGENTS.md` files and the active task contract. Preserve
unrelated work before investigating:

```console
git status --short
git branch --show-current
git rev-parse HEAD
git worktree list --porcelain
```

Admit the current root:

```console
kast workspace ensure
```

Kast reuses an eligible exact-root indexer or creates an isolated one. Continue
only when the command reports semantic readiness. Readiness and persisted graph
coverage are separate; retain the coverage returned by the operation under
test.

## Route the change to its owner

Use the narrowest row that fully owns the behavior.

| Change | Owning source | Focused proof |
| --- | --- | --- |
| Public CLI input | `cli-rs/src/interface/cli/agent/commands.rs` | Repository smoke tests and live `--help`. |
| Request construction | `cli-rs/src/agent/core/dispatch/commands.rs` | Repository and projection tests. |
| Query or result contract | `repository_intelligence/contract/` and `query/` | Validation and continuation tests. |
| Coverage or Gradle scope | `coverage/` and `workspace_inventory/` | Coverage and authority tests. |
| Discovery or labels | `discovery/` | Discovery and label-security tests. |
| Traversal or impact | `graph/` | Repository traversal and native graph tests. |
| Agent result shape | `cli-rs/src/agent/projection/repository/` | Projection-family tests. |
| Compiler graph extraction | `indexer/` | `./gradlew :indexer:test`. |
| SQLite rows or generation | `index-store/` | `./gradlew :index-store:test`. |
| Shared result models | `analysis-api/` | `./gradlew :analysis-api:test`. |
| Public docs | `docs/` | Docs contracts, LikeC4 validation, and site build. |

The Rust subsystem uses `include!`. Trace both the composition file and callers
before moving a function:

```console
sed -n '1,180p' cli-rs/src/semantics/repository_intelligence.rs

rg -n 'symbol_or_function_name' \
  cli-rs/src/semantics/repository_intelligence \
  cli-rs/src/agent/projection/repository \
  cli-rs/tests/repository_intelligence_smoke
```

Fix a shared invariant at the narrowest common boundary. Do not repeat the same
admission check in every output view.

## Trace every affected boundary

Before editing, trace each stage the behavior crosses:

1. CLI parsing and closed user input.
2. Request construction and exact-root routing.
3. Gradle scope and file coverage admission.
4. Read-only SQLite generation pinning.
5. Label and continuation verification, when present.
6. One intent executor.
7. Certainty and qualification construction.
8. Agent projection and output formatting.

For compiler production changes, trace the reverse path:

1. The operation parses exact Kotlin paths.
2. The indexer resolves PSI and K2 facts in one read boundary.
3. Diagnostics and source hashes admit each file.
4. A complete file replacement reaches `SemanticGraphWriter`.
5. Rows and generation change in one transaction.
6. Rust coverage admits the resulting `semantic_files` rows.

Do not hand-edit `source-index.db`, overlay descriptors, receipts, sockets, or
the active installation link.

## Prove one behavior first

Run a focused test that fails for the missing behavior before editing:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test repository_intelligence_smoke \
  <exact-test-name>
```

Keep user-facing regressions at the public boundary. A unit test can prove an
isolated parser or algorithm, but it does not replace RPC and projection proof
for a result-contract change.

After the focused proof passes, run the complete repository intelligence and
native graph tests:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test agent_graph_smoke \
  --test repository_intelligence_smoke
```

Run affected JVM modules when compiler models, extraction, or persistence
changed:

```console
./gradlew :analysis-api:test :indexer:test :index-store:test --no-daemon
```

Then use the repository-wide checks required by the task contract.

## Validate documentation changes

When public docs or architecture change, run:

```console
.github/scripts/docs/test-docs-content-contract.sh
.github/scripts/docs/test-docs-navigation-contract.sh
.github/scripts/docs/test-likec4-contract.sh
npm run diagrams:validate
zensical build --clean
```

Regenerate the checked-in LikeC4 module after the source model validates:

```console
npm run diagrams:embed
```

## Inspect a result before diagnosing code

Use a complete validated result when diagnosing coverage or certainty. Compact
and count views intentionally omit details:

```console
~/.local/share/kast/current/libexec/kastctl --output json agent repository \
  --workspace-root "$PWD" \
  --question "Resolve SemanticGraphSha256.parse exactly." \
  --intent resolve \
  --explain
```

Inspect these fields together:

- canonical workspace root;
- inventory and graph generations;
- scope and coverage;
- status, qualification, and truncation;
- limits, ordering, and continuation; and
- selected canonical identities and occurrence evidence.

Do not diagnose from a compact projection that omitted the evidence you need.

## Recover compiler graph evidence

Recovery must reestablish each authority in order. First admit the exact-root
indexer:

```console
kast workspace ensure
```

Then inspect its typed descriptor without choosing an implementation:

```console
~/.local/share/kast/current/libexec/kastctl --output json status \
  --workspace-root "$PWD"
```

Continue when the selected indexer is ready. Rerun the failed repository or
relationship operation with explanation enabled, then use its coverage limits
to choose exact refresh paths.

```console
~/.local/share/kast/current/libexec/kastctl agent graph \
  --workspace-root "$PWD" \
  --operation refresh \
  --file-path path/to/AffectedFile.kt
```

Repeat `--file-path` only for the affected set. Do not sweep the repository to
hide an unknown ownership problem. Restart a query without stale continuation
tokens, and rebuild labels only from the current canonical keys and content
hashes.

If compiler extraction fails, no semantic batch begins until all selected
files extract successfully. If a SQLite write fails, the replacement rolls
back. Repair the typed cause and retry the supported operation.

## Validate persistence invariants

When changing `SemanticGraphWriter` or schema code, prove that:

- old outgoing occurrences and removed declarations disappear;
- valid inbound edges survive when their target remains;
- boundary symbols are replaced by authoritative symbols after refresh;
- owner links are repaired after all symbols exist;
- overlay tombstones hide removed base rows and clear on refresh;
- generation increments once per successful transaction;
- any exception rolls back rows and generation together; and
- Rust and Kotlin consume the same source-index schema version.

Test schema-mismatch rebuild separately from current-version corruption. Do not
reinterpret the shared generation as graph-only state.

## Hand off exact evidence

A useful handoff records the exact source identity, changed authority, focused
red and green proof, broader checks, Git identity, and known limits. Keep local
implementation, local verification, remote CI, and publication as separate
claims.
