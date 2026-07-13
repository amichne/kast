# ADR 0021: First-class workspace file discovery

Status: Accepted

Date: 2026-07-13

This ADR supersedes the typed-agent-command list and workspace-file portions
of [ADR 0006](0006-forward-system-definition-and-audit-scope.md). It extends
the exact-root admission contract in
[ADR 0019](0019-exact-root-semantic-workspace-admission.md). The public agent
surface now includes `kast agent workspace-files`; arbitrary raw RPC dispatch
remains internal.

## Decision

Kast exposes `kast agent workspace-files` as the stable public path for
discovering Kotlin source and Kotlin script files owned by the admitted
semantic workspace. The command does not begin with a recursive filesystem
walk or a Git file list. It composes evidence from the existing
`raw/workspace-files` backend method and the exact-root SQLite source index,
then annotates those candidates with targeted filesystem metadata and Git
dirty state.

The Rust CLI owns this composition. The existing Kotlin API, server, and
backend `WorkspaceFilesQuery` and `WorkspaceFilesResult` wire contract does not
change in this issue. The backend remains authoritative for compiler
project-model ownership and module membership. The source index remains
authoritative only for facts it actually stores: manifest membership, Gradle
module path, source set, and package fully qualified name. An index row alone
does not prove current semantic ownership.

The internal `WorkspaceInventorySnapshot` is not subject to the public
command's result limit or filters. It retains every Kotlin candidate returned
by the available backend and source-index snapshots, plus source completeness
and limitation evidence. Issue #340 may consume that internal inventory to
classify Gradle Kotlin DSL scripts without reimplementing file discovery or
mistaking index presence for project-model ownership. Source-layer limits are
not hidden: if the existing backend reports a truncated module, the internal
snapshot is partial even though the Rust collector itself is uncapped.

## Public command contract

The command accepts the standard exact-root `--workspace-root` and `--backend`
flags and the result-view flags introduced by ADR 0020. Its discovery filters
are typed and conjunctive:

- `--module` matches an exact backend module name or exact indexed Gradle
  module path;
- `--source-set` matches an exact indexed source set;
- `--kind source|script` distinguishes `.kt` from `.kts`;
- `--package` matches an exact indexed package fully qualified name;
- `--dirty clean|dirty|unknown` filters the typed Git state class;
- `--drift none|filesystem-only|index-only|missing-on-disk|unknown` filters
  cross-source drift;
- `--path-prefix` accepts one normalized workspace-relative path prefix;
- `--glob` accepts one bounded glob over normalized workspace-relative paths;
  and
- `--limit` defaults to 20 and accepts 1 through 200.

Absolute path prefixes, parent traversal, empty semantic selectors, invalid
package names, regex-prefixed globs, and out-of-range limits fail at the typed
CLI boundary. Filters never widen the inventory and are applied before the
public limit. Results sort by normalized workspace-relative path, then module
identity, so the same evidence snapshot produces deterministic JSON and TOON.

The compact default emits a typed result with the exact workspace root,
bounded file records, known-match and returned counts, truncation and
inventory-completeness facts, typed limitations, and schema version. Each file
record includes:

- absolute `filePath` and workspace-relative `relativePath`;
- backend module name and indexed Gradle module path when known;
- source set and package when known;
- `KOTLIN_SOURCE` or `KOTLIN_SCRIPT` kind;
- `INDEXED`, `NOT_INDEXED`, or `UNKNOWN` index state;
- `NONE`, `FILESYSTEM_ONLY`, `INDEX_ONLY`, `MISSING_ON_DISK`, or `UNKNOWN`
  drift;
- detailed dirty state collapsed by the public dirty filter into clean,
  dirty, or unknown; and
- verbose/explain evidence identifying which sources established the record.

The default compact representation must remain within 120 lines and 1,500
estimated tokens for a high-cardinality fixture. `--fields` selects the typed
file fields, `--count` reports known cardinalities without file payloads, and
`--verbose` or `--explain` exposes source coverage and evidence without making
raw transport envelopes the default.

`filePath` is the direct composition key for
`kast agent diagnostics --file-path <path>` and
`kast agent symbol --query <name> --file-hint <path>`. The public command does
not invent a second path dialect.

## Evidence and drift rules

The collector opens the configured exact-root source-index database read-only
and reads a single SQLite snapshot joining `file_manifest`, `path_prefixes`,
`file_metadata`, and `fq_names`. It keeps only `.kt` and `.kts` candidates.
Existing files are checked individually; the implementation does not recurse
from the workspace root. Git porcelain output may annotate a candidate but
must never add a candidate that is absent from both backend and index
evidence.

Every candidate is normalized against the admitted workspace root. Existing
paths whose canonical target leaves that root and index paths that are
lexically outside it are omitted with a typed limitation. This prevents stale
absolute index entries or another checkout's paths from becoming current-root
workspace evidence.

Drift is classified by this truth table:

| Backend ownership | Index snapshot | Filesystem | Backend coverage | Result |
| --- | --- | --- | --- | --- |
| Present | Present | Present | Any | `NONE`, `INDEXED` |
| Present | Absent | Present | Any | `FILESYSTEM_ONLY`, `NOT_INDEXED` |
| Absent | Present | Any | Complete | `INDEX_ONLY`, `INDEXED` |
| Absent | Present | Any | Truncated or unavailable | `UNKNOWN`, `INDEXED` |
| Present or index-present | Any | Missing | Any | `MISSING_ON_DISK` with the independently proven index state |
| Present | Index unavailable | Present | Any | `UNKNOWN`, `UNKNOWN` |

`INDEX_ONLY` is therefore impossible when backend file enumeration is
truncated or unavailable. A truncated module makes absence unprovable for that
module. If the backend response cannot associate an indexed row with one
complete module, absence remains unknown. This rule also prevents source-index
rows from nested `.worktrees` or stale checkouts from posing as current
project-model ownership.

Backend capability absence, backend enumeration truncation, unavailable or
incompatible source index, unavailable Git status, missing package metadata,
and excluded out-of-root rows are distinct typed limitations. A usable
backend-only or index-only snapshot may return partial evidence. Exact-root
admission failure and malformed backend payloads fail closed. When neither
backend nor index can supply candidates, the command returns
`WORKSPACE_FILE_DISCOVERY_UNAVAILABLE` instead of a false empty success.

## Capability callability invariant

Rust owns a typed public-capability route registry. Its first entry maps the
backend `WORKSPACE_FILES` capability to
`kast agent workspace-files`. Verification projects this capability as public
only when the backend advertises it and the registered Clap command is
callable. A contract test walks the same registry against the generated Clap
command tree. A backend capability may remain visible as raw/internal evidence
for diagnostics, but it cannot be presented as a public workspace-discovery
route without a passing callable-command assertion.

Issue #342 may extend this registry to every public capability. This issue
establishes the invariant and covers `WORKSPACE_FILES`; it does not duplicate
the entire RPC catalog in prose or promote `raw/workspace-files` to a public
agent workflow.

## Ownership

- `cli-rs/src/workspace_inventory.rs` and
  `cli-rs/src/workspace_inventory/` own the reusable uncapped inventory,
  exact-root index reader, targeted filesystem evidence, dirty-state
  annotation, composition, and internal types.
- `cli-rs/src/agent/workspace_files.rs` owns public command execution and
  typed filter validation.
- `cli-rs/src/agent/projection/workspace_files.rs` owns compact, selected,
  count, verbose, and explain projections after ADR 0020 lands.
- `cli-rs/src/agent/public_capabilities.rs` owns the public capability route
  registry and verification mapping.
- `cli-rs/src/cli/agent.rs` owns the typed Clap command and arguments.
- `cli-rs/tests/agent_workspace_files_smoke.rs` owns public discovery,
  limitation, budget, and composition regressions.
- `docs/reference/agent-commands.md` and the packaged Kast skill teach the
  typed public command. Generated raw RPC catalog and protocol artifacts stay
  unchanged because the existing Kotlin wire method is reused.

The new inventory directory receives a scoped `AGENTS.md` when production
implementation begins because it creates a new source-ownership boundary.

## Validation

Implementation must use red-green slices and run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test cli_core_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

The implementation does not require an `analysis-api`, `analysis-server`,
backend, index schema, or generated protocol change. If implementation reveals
that the existing raw contract cannot preserve the evidence rules above, stop
and supersede this decision before changing Kotlin wire types.

## Change rule

Future work may add pagination, new file kinds, or Gradle Kotlin DSL subtype
evidence additively. It must preserve exact-root containment, the uncapped
internal snapshot, deterministic public bounds, and the rule that incomplete
backend evidence cannot prove `INDEX_ONLY`. Any change that uses filesystem or
Git enumeration as the candidate authority requires a superseding ADR.
