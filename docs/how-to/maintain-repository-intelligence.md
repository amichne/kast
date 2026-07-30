---
type: How-to Guide
title: How to Maintain Repository Intelligence
description: Operate and release Kast's compiler-backed repository query path.
tags: [maintenance, repository-intelligence, validation, recovery, release]
code_sources:
  - path: cli-rs/src/semantics/repository_intelligence.rs
  - path: backend-idea/src/main/kotlin/io/github/amichne/kast/idea/backend/semantic/SemanticGraphOperations.kt
  - path: index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/sqlite/semantic/SemanticGraphWriter.kt
  - path: benchmarks/repository-intelligence/spec/manifest.json
  - path: .github/workflows/release.yml
  - path: scripts/release/verify-release-state.sh
---

# How to Maintain Repository Intelligence

Use this guide when changing the repository question contract, compiler graph
production, SQLite persistence, query algorithms, projection, tests,
benchmarks, or release evidence. It assumes the architecture described in
[Repository intelligence architecture](../explanation/repository-intelligence.md).

The shortest safe maintenance path is to identify the authority that owns the
behavior, run its focused proof, and broaden validation only when a shared
contract moved.

For every change, follow **Start → Route → Trace → Prove → Broaden**. Use the
diagnosis, persistence, benchmark, and release sections only when that
authority is in scope.

## Start from the exact worktree

Read the nearest `AGENTS.md` files before investigating or editing. The root
task contract and module-specific instructions define allowed writes,
verification, generated-file boundaries, and platform constraints.

Confirm the checkout and preserve unrelated work:

```console
git status --short
git branch --show-current
git rev-parse HEAD
git worktree list --porcelain
```

On macOS, admit the current root through the supported IDEA pathway:

```console
_kastctl developer runtime up \
  --workspace-root "$PWD" \
  --backend idea \
  --accept-indexing
```

An `INDEXING` response is progress, not green proof. Runtime readiness and
persisted graph completeness are separate: require `selected.ready` to be
`true`, then inspect the coverage returned by the repository or relationship
operation under test. Runtime status does not report graph coverage. A runtime
can be `READY` while the task result remains incomplete. Do not open another
IDE process or substitute a runtime attached to another worktree.

## Route the change to its owner

Use the narrowest row that fully owns the behavior. A change spanning rows
usually needs proof for each affected boundary.

| Change | Owning source | Focused proof |
| --- | --- | --- |
| CLI flags and typed user input | `cli-rs/src/interface/cli/agent/commands.rs` | Repository smoke tests and live `--help`. |
| CLI-to-RPC request construction | `cli-rs/src/agent/core/dispatch/commands.rs` | Repository and projection tests. |
| Request, label, continuation, or result contract | `repository_intelligence/contract/` and `query/` | Validation, continuation, and agent-view smoke groups. |
| Coverage, eligibility, or Gradle scope | `coverage/` and `workspace_inventory/` | Coverage and authority smoke groups. |
| Resolve, regex, ranking, or labels | `discovery/` | Discovery, label, and label-security smoke groups. |
| Path or impact traversal | `graph/` | Repository traversal tests and `agent_graph_smoke`. |
| Architecture findings | `architecture/` | Architecture limit and agent-view tests. |
| Repository context | `context/` | Context and root-authority tests. |
| Agent-facing result shape | `cli-rs/src/agent/projection/repository/` | Repository projection-family and view tests. |
| Host-neutral semantic graph model | `analysis-api/` | `./gradlew :analysis-api:test`. |
| IDEA compiler extraction | `backend-idea/` | `./gradlew :backend-idea:test`. |
| SQLite graph rows or generation | `index-store/` | `./gradlew :index-store:test`. |
| Schema version generation | `build-logic/` and schema source | Generator test plus Rust schema smoke proof. |
| Authored CLI protocol | `cli-rs/protocol/source/commands.json` | Generated-contract check; do not edit generated output. |
| Public docs and navigation | `docs/` and `zensical.toml` | Both docs contracts and Zensical build. |
| Benchmark proof | `benchmarks/repository-intelligence/` | Manifest checks and an intentional benchmark capture. |
| Beta publication | `.github/workflows/release.yml` and release scripts | Release contracts, exact tag jobs, published-state verifier. |

The Rust subsystem is composed with `include!`, so a source filename may not
look like a conventional public module. Search the composition file and every
caller before splitting or moving a function:

```console
sed -n '1,180p' \
  cli-rs/src/semantics/repository_intelligence.rs

rg -n 'symbol_or_function_name' \
  cli-rs/src/semantics/repository_intelligence \
  cli-rs/src/agent/projection/repository \
  cli-rs/tests/repository_intelligence_smoke
```

Fix a shared invariant at the narrowest common boundary. For example, required
semantic-table admission belongs in the shared discovery loader, not in each
CLI view that happens to expose discovery.

## Trace every affected boundary

Before editing, trace the behavior through the stages it actually crosses:

1. CLI parsing and closed user input.
2. RPC request construction and exact-root routing.
3. Gradle scope and file coverage admission.
4. Read-only SQLite generation pinning.
5. Optional label and continuation verification.
6. One intent executor.
7. Certainty and qualification construction.
8. Agent projection and output formatting.

A change is incomplete while any affected stage encodes the old contract.
Trace the complete authority chain described in
[Repository intelligence architecture](../explanation/repository-intelligence.md).

For Kotlin production changes, trace the other direction as well:

1. `SemanticGraphQuery` parses exact Kotlin paths.
2. IDEA resolves PSI and K2 facts in a read action.
3. Diagnostics and source hashes admit each file.
4. `SemanticGraphFileIndexUpdate` carries the complete file replacement.
5. `SemanticGraphWriter` updates rows and generation in one transaction.
6. Rust coverage admits the resulting `semantic_files` rows.

Do not hand-edit `source-index.db`, overlay descriptors, receipts, sockets, or
the active installation link. Those are outputs of typed operations, not
maintainer configuration.

## Prove one behavior first

Use a focused test that fails for the missing behavior before changing source.
The repository smoke binary supports a name filter:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test repository_intelligence_smoke \
  <exact-test-name>
```

Keep the test at the public boundary when the regression affects users. A
focused unit test is appropriate for an isolated parser or algorithm, but it
does not replace the RPC and projection proof for a result-contract change.

The current semantic-table recovery contract has a focused public check:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test repository_intelligence_smoke \
  repository_missing_semantic_tables_rejects_instead_of_empty
```

When the focused proof passes, run the complete repository intelligence
binary:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test repository_intelligence_smoke
```

Add `agent_graph_smoke` when shared native graph storage, generation, or
traversal behavior changed:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test agent_graph_smoke \
  --test repository_intelligence_smoke
```

## Broaden validation by changed authority

Rust-wide proof catches formatting, lint, feature, and target interactions:

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check

cargo clippy --manifest-path cli-rs/Cargo.toml --locked \
  --all-targets --all-features -- -D warnings

cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --all-targets --all-features
```

If compiler models, IDEA extraction, or persistence changed, run the affected
Kotlin modules before the broad JVM gate:

```console
./gradlew :analysis-api:test
./gradlew :backend-idea:test
./gradlew :index-store:test
./gradlew test --no-daemon
./gradlew buildIdeaPlugin --no-daemon
```

The repository shape gate enforces no more than 400 physical lines in tracked
`.kt`, `.kts`, and `.rs` files and no more than 10 direct tracked children in a
directory:

```console
bash .github/scripts/test-repository-shape-contract.sh
python3 .github/scripts/check-repository-shape.py
```

When public docs change, validate their exact page set, navigation, and
rendered site:

```console
.github/scripts/docs/test-docs-content-contract.sh
.github/scripts/docs/test-docs-navigation-contract.sh
zensical build --clean --strict
```

Use `git diff --check` and inspect the final path-restricted diff before
staging. A green unrelated test run does not prove the requested change.

## Inspect a result before diagnosing code

Use the complete validated view when diagnosing coverage or certainty. Compact
and count views intentionally omit details:

```console
_kastctl --output json agent repository \
  --workspace-root "$PWD" \
  --question "Resolve SemanticGraphSha256.parse exactly." \
  --intent resolve \
  --explain
```

Inspect these fields together:

- `workspaceIdentity.canonicalRoot`
- `generation`, `inventoryGeneration`, and `graphGeneration`
- `scope` and `coverage`
- `status`, `qualification`, and `truncated`
- `bounds`, `ordering`, and both continuation fields
- selected canonical identities and their occurrence evidence

Use the
[certainty explanation](../explanation/repository-intelligence.md#certainty-is-a-result-property)
to check how coverage and truncation constrain an internal result.

## Diagnose by failed authority

Start with the typed code and the result phase. Avoid changing index files or
loosening validation to make an error disappear.

| Symptom or code | Inspect | Recovery |
| --- | --- | --- |
| Wrong canonical root | Command root and runtime identity. | Rerun from the intended exact worktree. |
| `IDEA_PLUGIN_UPDATE_REQUIRED` | Installed CLI/plugin release pair. | Update Kast; restart only when the typed result requests it. |
| `IDEA_VERSION_UNSUPPORTED` | JetBrains product and build. | Use a supported host build. |
| `IDEA_HOST_AMBIGUOUS` | Running and configured supported hosts. | Select one exact supported host. |
| `INDEXING` | Gradle import, smart mode, Kotlin admission, reference index. | Wait and retry readiness; do not bypass it. |
| `INVALID_REPOSITORY_SCOPE` | Requested Gradle project or source set. | Use an identity present in the workspace inventory. |
| `AMBIGUOUS_REPOSITORY_SCOPE` | Included builds or repeated project names. | Supply the build-qualified identity. |
| `GRAPH_COVERAGE_UNSTABLE` | Source-index generation movement. | Let indexing settle, then retry coverage. |
| `REPOSITORY_QUERY_UNSTABLE` | Generation moved between admission and execution twice. | Retry after the index quiesces. |
| `REPOSITORY_COVERAGE_INCOMPLETE` | A positive result lacks complete compiler coverage. | Restore exact-root readiness, refresh incomplete files, and retry. |
| `REPOSITORY_INDEX_INVALID` | Schema and required semantic tables. | Rebuild compiler evidence through the supported runtime. |
| Invalid or stale continuation | Root, query, scope, generation, and schema. | Restart the query; never edit the token. |
| Invalid or stale label index | Path, artifact schema, key, and content hash. | Regenerate labels from the active compiler snapshot. |
| `REPOSITORY_CONTEXT_CHANGED` | Context file replacement during read. | Retry after the working tree stops changing. |

Terminal host errors remain blockers. Do not substitute headless analysis on
macOS when the exact-root IDEA pathway reports an unsupported or ambiguous
host.

## Recover compiler graph evidence

Recovery must reestablish each authority in order. First bring up the exact
workspace runtime:

```console
_kastctl developer runtime up \
  --workspace-root "$PWD" \
  --backend idea \
  --accept-indexing
```

Then inspect runtime and graph state:

```console
_kastctl --output json status \
  --workspace-root "$PWD" \
  --backend idea
```

Continue when `selected.ready` is `true`. Rerun the failed repository or
relationship operation with `--explain`, then use its coverage limitations to
choose targeted refreshes. Refresh the affected Kotlin file through the
compiler-backed graph operation:

```console
_kastctl agent graph \
  --workspace-root "$PWD" \
  --operation refresh \
  --file-path path/to/AffectedFile.kt
```

Repeat `--file-path` for the exact affected set. Do not sweep the repository
merely to hide an unknown ownership or coverage problem.

Rerun the repository query without old continuation tokens. Inspect coverage
before regenerating any label artifact. Labels must be rebuilt from the
refreshed canonical keys and source content hashes; otherwise the verifier
must reject them as stale.

If compiler extraction fails, no semantic batch write begins until all
selected files have extracted successfully. Fix the reported diagnostic or
unsupported target, then rerun the refresh. If a SQLite write fails, the
replacement transaction rolls back; resolve the underlying storage error and
retry the supported operation.

## Validate persistence invariants

Persistence changes have failure modes that ordinary query tests can miss.
Check all of these behaviors when editing `SemanticGraphWriter` or schema code:

- Old outgoing occurrences and removed declarations disappear.
- Valid inbound edges survive when their target remains.
- Boundary symbols are replaced by authoritative symbols when refreshed.
- Owner links are repaired after all symbols exist.
- Overlay tombstones hide removed base rows and clear on refresh.
- Generation increments once per successful transaction.
- Any exception rolls back rows and generation together.
- Rust and Kotlin consume the same source-index schema version.

- Verify that the shared generation still covers inventory and reference-index
  writes; never reinterpret it as a graph-only counter.
- Test schema-mismatch rebuild separately from current-version corruption.
  Preserve structural failure evidence instead of assuming a rebuild occurred.

## Refresh benchmark evidence intentionally

The checked-in benchmark is a frozen corpus with recorded source, binary,
index, question, and rubric provenance. It is historical evidence unless its
`implementationCommit` equals the release candidate.

Run the benchmark only when release or performance proof is required, because
it writes result artifacts:

```console
./benchmarks/repository-intelligence/run.sh --assert --repeat 2
```

That command writes the uncommitted candidate capture to `results/latest.json`.
Require exact source identity before treating it as release evidence:

```console
test "$(jq -r '.implementationCommit' \
  benchmarks/repository-intelligence/results/latest.json)" = \
  "$(git rev-parse HEAD)"
```

Also require a clean recorded source status, deterministic repeated answers,
zero critical failures, unchanged corpus identity, and stable source-index
identity before and after capture. Do not cite a prior benchmark as current
release proof merely because the question set still passes. Promote a capture
to the tracked `final.json` only as an intentional benchmark update with its
own reviewed diff.

## Assemble beta release evidence

Treat local validation, the pushed commit, the release tag, and published
assets as four different checkpoints. Capture the exact SHA at each boundary.

Before dispatch, require:

1. The intended diff is committed and the worktree is clean.
2. The branch is pushed and the remote head equals the local head.
3. Focused, full, shape, docs, and release-contract checks are green.
4. Benchmark output, when claimed, records that exact head.
5. Known certainty and recovery gaps are either fixed or explicitly accepted
   as beta limitations.

The release workflow accepts the `beta` release type and creates a tag shaped
as `v<next-patch>-beta.<short-sha>`. Dispatch it only for the reviewed remote
head:

```console
gh workflow run release.yml \
  --ref <reviewed-branch> \
  -f release_type=beta
```

The dispatch run performs preflight validation and pushes the tag. A separate
tag-triggered run builds and publishes the assets. Do not call the beta
published while either run is pending or failed.

After the release is published, verify the immutable remote state:

```console
scripts/release/verify-release-state.sh --tag <beta-tag>
```

Record the branch SHA, tag target SHA, both workflow conclusions, release URL,
asset verification result, and the machine installation result. The release
verifier's bundle check is publication evidence; installation on the intended
corporate macOS host is separate acceptance evidence.

## Hand off with proof, not confidence

A useful maintainer handoff states what changed, which authority owns it, the
focused red and green proof, every broader command run, exact Git and remote
identities, benchmark provenance, and known limitations.

Do not collapse these states:

- Implemented locally
- Verified locally
- Committed
- Pushed
- Reviewed
- CI green
- Tagged
- Published
- Installed and accepted

The subsystem is ready to publish only when the checkpoint being claimed has
its own evidence and no known gap contradicts the claim.
