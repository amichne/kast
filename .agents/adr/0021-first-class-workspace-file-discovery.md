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
walk or a Git file list. It composes compiler/project-model candidates from a
paged `raw/workspace-files` backend method with Kotlin-source candidates from
the exact-root SQLite source index, then annotates those candidates with
targeted filesystem metadata and Git dirty state.

The Kotlin API, server, and backend `WorkspaceFilesQuery` and
`WorkspaceFilesResult` contract gains an opaque workspace snapshot token,
opaque per-module page cursors, and explicit module content roots alongside
source roots. Rust first reads the sorted module inventory and its snapshot
token, then exhausts every module page against that exact generation before
classifying backend absence. The backend gathers Kotlin sources and scripts
from IntelliJ's compiler/project model, including Gradle-linked root
`build.gradle.kts` and `settings.gradle.kts`, convention-plugin scripts, and
ordinary project scripts. It does not recursively scan the filesystem for
request candidates.

The backend is authoritative for current compiler/project-model ownership and
may report one physical path as owned by multiple modules. The source index is
authoritative only for Kotlin-source facts it actually stores: manifest
membership, Gradle module path, source set, and package fully qualified name.
`SourceIndexFilePolicy` remains `.kt`-only. A `.kts` record is therefore
`NOT_APPLICABLE` to this Kotlin source index rather than `NOT_INDEXED`; issue
#340 owns a separate Gradle DSL index. An index row alone does not prove current
semantic ownership.

The Rust CLI owns evidence composition. Its internal
`WorkspaceInventorySnapshot` is not subject to public filters or limits. It
retains every candidate returned by all successfully exhausted backend pages
and the Kotlin source-index snapshot, plus per-module completeness and
limitation evidence. Issue #340 consumes the `.kts` candidates but writes
Gradle declarations and relationships to its separate Gradle DSL index. If a
generic backend page transport fails or returns an invalid token, only that
module is partial and backend absence remains unprovable for paths that may
belong to it. A stale workspace generation invalidates the whole backend
attempt: Rust discards it,
restarts once from fresh metadata, and returns typed partial evidence if that
single retry also becomes stale. The partial result carries
`BACKEND_WORKSPACE_INVENTORY_STALE`, marks every module from the last metadata
response incomplete, and retains no backend candidates from either stale
attempt. A typed project-model-incomplete response is also workspace-wide:
Rust discards every backend candidate from that attempt without consuming the
stale retry and preserves the server's typed reason as a public limitation.

## Raw paging and project-model authority

An unpaged `raw/workspace-files` metadata request lists sorted module metadata
and counts plus an opaque top-level `snapshotToken`. Rust echoes that token on
every exact-module request with `includeFiles=true` and a positive
server-bounded `maxFilesPerModule`. Each opaque `nextPageToken` is server-owned
and binds the workspace generation, exact module identity, and positive next
offset. Clients never decode or construct either token.

The backend builds a canonical full-workspace inventory in one read action and
fingerprints its sorted module identities, source/content roots, dependencies,
and candidate paths. Before serving any exact-module page it recomputes that
inventory and compares its generation with the echoed snapshot and decoded
cursor. Equal-cardinality path replacement, module addition/removal, or any
other inventory change fails with `STALE_WORKSPACE_INVENTORY`; a malformed,
out-of-range, or cross-module cursor fails as
`INVALID_WORKSPACE_FILE_CURSOR`. Only a matching generation may be sliced.
The backend returns stable `fileCount`, `returnedFileCount`, and
`nextPageToken` values. Rust additionally rejects repeated or non-advancing
cursors, inconsistent totals, changed module identity, and overlapping pages
before it marks the module complete.

The backend candidate provider uses IntelliJ project scope, module content and
source roots, and linked Gradle project roots. Known Gradle project roots make
root build and settings scripts project-model candidates without a recursive
walk. Project-scope Kotlin scripts supply convention-plugin and ordinary
scripts. Each candidate carries every owning backend module; root-level
project scripts use every root Gradle module associated with their linked
project root. The IDEA 2025.3 adapter reads
`GradleProjectSettings.getCompositeBuild()`,
`GradleProjectSettings.CompositeBuild.getCompositeParticipants()`, and
`BuildParticipant.getRootPath()` for included-build roots, then associates
modules through `GradleModuleDataIndex.findGradleModuleData(Module)`,
`GradleModuleData.isIncludedBuild()`, `getGradleProjectDir()`, and
`ModuleData.getLinkedExternalProjectPath()`. The backend converts IDEA indexing,
unavailable Gradle project-model data, and a linked root that cannot be
associated with a backend module into
`WorkspaceProjectModelIncompleteException`. Its stable
`WORKSPACE_PROJECT_MODEL_INCOMPLETE` code uses status 503, is retryable, and
carries one typed reason in `details.reason`: `RUNTIME_INDEXING`,
`PROJECT_MODEL_UNAVAILABLE`, or `LINKED_ROOT_UNASSOCIATED`. Included builds
retain their own linked roots and module owners.

Rust maps those reasons to distinct `BACKEND_RUNTIME_INDEXING`,
`BACKEND_PROJECT_MODEL_UNAVAILABLE`, and
`BACKEND_LINKED_ROOT_UNASSOCIATED` limitations. Failure of the metadata request
makes backend coverage unavailable. The same typed failure while paging makes
the backend attempt workspace-wide partial and discards its earlier pages;
generic transport or cursor failure remains local to the requested module.

## Public command contract

The command accepts the standard exact-root `--workspace-root` and `--backend`
flags and the result-view flags introduced by ADR 0020. Its discovery filters
are typed and conjunctive:

- `--module` matches any exact backend module name or indexed Gradle module
  path in the record's ownership sets;
- `--source-set` matches an exact indexed source set;
- `--kind source|script` distinguishes `.kt` from `.kts`;
- `--package` matches an exact indexed package fully qualified name;
- `--dirty clean|dirty|unknown` filters the typed Git state class;
- `--drift none|filesystem-only|index-only|missing-on-disk|not-applicable|unknown`
  filters cross-source drift;
- `--path-prefix` accepts one normalized workspace-relative path prefix;
- `--glob` accepts one bounded glob over normalized workspace-relative paths;
  and
- `--limit` defaults to 20 and accepts 1 through 200.

Absolute path prefixes, parent traversal, empty semantic selectors, invalid
package names, regex-prefixed globs, and out-of-range limits fail at the typed
CLI boundary. Filters never widen the inventory and are applied before the
public limit. Results sort by normalized workspace-relative path; every
ownership set sorts by its typed module identity. The same evidence snapshot
therefore produces deterministic JSON and TOON.

The compact default emits a typed result with the exact workspace root,
bounded file records, ADR 0020's discriminated `EXACT` or `KNOWN_MINIMUM`
cardinality, returned count, truncation, separate candidate-inventory and
filter-evidence coverage, typed limitations, and schema version. Each file
record includes:

- absolute `filePath` and workspace-relative `relativePath`;
- sorted backend-module and indexed-Gradle-module ownership sets;
- source set when known;
- `KOTLIN_SOURCE` or `KOTLIN_SCRIPT` kind;
- `INDEXED`, `NOT_INDEXED`, `NOT_APPLICABLE`, or `UNKNOWN` Kotlin source-index
  state;
- `NONE`, `FILESYSTEM_ONLY`, `INDEX_ONLY`, `MISSING_ON_DISK`,
  `NOT_APPLICABLE`, or `UNKNOWN` drift;
- `NAMED`, `ROOT`, `UNAVAILABLE`, or `INVALID_REFERENCE` package evidence,
  with a package name only for `NAMED`;
- detailed dirty state collapsed by the public dirty filter into clean,
  dirty, or unknown; and
- verbose/explain evidence identifying which sources established the record.

The candidate inventory is `COMPLETE` only when every candidate authority that
could contribute a matching path is exhaustive. Filter evidence is `COMPLETE`
only when every predicate used by the request is known for every candidate. A
complete candidate inventory can therefore coexist with partial filter evidence:
for example, unavailable Git status makes a `--dirty clean` query inexact even
when all backend and source-index candidates are known. Cardinality is `EXACT`
only when both relevant coverage dimensions are complete; otherwise it is
`KNOWN_MINIMUM` and counts only matches actually proved. `returnedCount` equals
the emitted file count. `truncated` is true when an exact total exceeds that
count or cardinality is `KNOWN_MINIMUM`, because unseen matches remain possible.

The default compact representation must remain within 120 lines and 1,500
estimated tokens for a high-cardinality fixture. `--fields` selects typed file
fields, `--count` reports the same typed overall cardinality plus discriminated
cardinalities for grouped counts without file payloads, and `--verbose` or
`--explain` exposes source coverage and evidence without making raw transport
envelopes the default.

`filePath` is the direct composition key for
`kast agent diagnostics --file-path <path>` and
`kast agent symbol --query <name> --file-hint <path>`. The public command does
not invent a second path dialect.

## Candidate, path, package, and Git evidence

The collector opens the configured exact-root source-index database read-only
and reads one SQLite snapshot joining `file_manifest`, `path_prefixes`,
`file_metadata`, and `fq_names`. It keeps only `.kt` candidates because that is
the source index's declared policy. Existing candidates are checked
individually; the implementation does not recurse from the workspace root.
Git porcelain may annotate a candidate but never adds one absent from backend
and index evidence.

Every candidate is normalized against the admitted workspace root. Existing
paths whose canonical target leaves that root and index paths that are
lexically outside it are omitted with a typed limitation. Backend module
source/content roots receive the same canonical containment proof before they
can establish ownership or completeness.

The dirty-state adapter forces `status.relativePaths=false` so Git porcelain v2
paths are repository-root-relative even when Git runs with
`-C <workspace-root>`. It resolves and canonicalizes the Git top level, proves
the admitted workspace is contained by it, restricts status to that workspace,
strips the exact workspace prefix from current and original rename paths, and
only then matches normalized inventory keys. A rename with one endpoint
outside the workspace annotates only its contained endpoint. Failure to prove
this mapping produces `DIRTY_STATE_UNAVAILABLE`; unmatched nested-workspace
paths never become false `CLEAN` evidence.

The index query selects a metadata-row marker, `package_fq_id`, and the joined
package name separately. No metadata row is `UNAVAILABLE`; a present row with
null `package_fq_id` proves `ROOT`; a non-null id with one joined name is
`NAMED`; and a non-null id without a joined row is `INVALID_REFERENCE` plus
`PACKAGE_METADATA_INVALID`. These states are never collapsed into one null.

## Drift and completeness rules

| Kind | Backend ownership | Kotlin source index | Filesystem | Relevant backend coverage | Result |
| --- | --- | --- | --- | --- | --- |
| `.kt` | Present | Present | Present | Any | `NONE`, `INDEXED` |
| `.kt` | Present | Absent | Present | Any | `FILESYSTEM_ONLY`, `NOT_INDEXED` |
| `.kt` | Absent | Present | Present | Complete for every possible owner | `INDEX_ONLY`, `INDEXED` |
| `.kt` | Absent | Present | Present | Partial or unavailable for any possible owner | `UNKNOWN`, `INDEXED` |
| `.kt` | Present or index-present | Any | Missing | Any | `MISSING_ON_DISK` with independently proven index state |
| `.kt` | Present | Unavailable | Present | Any | `UNKNOWN`, `UNKNOWN` |
| `.kts` | Present | Not queried | Present | Complete page evidence | `NOT_APPLICABLE`, `NOT_APPLICABLE` |
| `.kts` | Present | Not queried | Missing | Any | `MISSING_ON_DISK`, `NOT_APPLICABLE` |

`INDEX_ONLY` is impossible when any module that could own the path has
incomplete paging. Physical-file ownership is a sorted set. Shared and
overlapping roots preserve every backend owner; duplicate paths are not
malformed merely because their module names differ. If an indexed row cannot
be associated with completely paged possible owners, absence remains unknown.

Backend capability absence, metadata or page failure, runtime indexing,
unavailable Gradle project-model data, unassociated linked roots, repeated
snapshot staleness, unavailable or incompatible source index, unavailable Git
status, unavailable or invalid package metadata, and excluded out-of-root rows
are distinct typed limitations. A usable backend-only or index-only snapshot
may return partial evidence. Exact-root admission failure and malformed backend
payloads fail closed. When neither backend nor index is usable, the command
returns `WORKSPACE_FILE_DISCOVERY_UNAVAILABLE` instead of a false empty success.

## Capability callability invariant

Rust owns a typed public-capability route registry. Its first entry maps the
backend `WORKSPACE_FILES` capability to `kast agent workspace-files`.
Verification projects this capability as public only when the backend
advertises it and the registered Clap command is callable. A contract test
walks the same registry against the generated Clap command tree. A backend
capability may remain visible as raw/internal evidence for diagnostics, but it
cannot be presented as a public workspace-discovery route without a passing
callable-command assertion.

Issue #342 may extend this registry to every public capability. This issue
establishes the invariant and covers `WORKSPACE_FILES`; it does not duplicate
the entire RPC catalog in prose or promote `raw/workspace-files` to a public
agent workflow.

## Ownership

- `analysis-api`, `analysis-server`, and `backend-idea` own typed deterministic
  per-module workspace-file paging, source/content-root evidence,
  compiler/project-model `.kt`/`.kts` enumeration, and typed project-model
  incompleteness. Generated protocol artifacts come from those Kotlin source
  owners.
- `cli-rs/src/workspace_inventory.rs` and
  `cli-rs/src/workspace_inventory/` own the reusable uncapped inventory,
  exact-root Kotlin source-index reader, targeted filesystem evidence,
  dirty-state annotation, composition, and internal types.
- `cli-rs/src/agent/workspace_files.rs` owns public command execution and typed
  filter validation.
- `cli-rs/src/agent/projection/workspace_files.rs` owns compact, selected,
  count, verbose, and explain projections after ADR 0020 lands.
- `cli-rs/src/agent/public_capabilities.rs` owns the public capability route
  registry and verification mapping.
- `cli-rs/src/cli/agent.rs` owns the typed Clap command and arguments.
- `cli-rs/tests/agent_workspace_files_smoke.rs` owns public discovery,
  limitation, budget, exact-root, and composition regressions.
- `docs/reference/agent-commands.md` and the packaged Kast skill teach the
  typed public command.
- `cli-rs/resources/kast-skill/references/commands.json` is the hand-authored
  internal raw-command catalog. Generated YAML, request schemas, and request
  samples derive from it.

The new inventory directory receives a scoped `AGENTS.md` when implementation
begins because it creates a new source-ownership boundary. `backend-idea`
receives its nearest ownership guide for the project-model inventory and Gradle
bridge. `analysis-api` and generated-contract ownership guidance is updated
with the wire change.

## Validation

Implementation must use red-green slices and run:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test cli_core_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test semantic_workspace_admission_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
./gradlew :analysis-api:test :analysis-server:test :index-store:test :backend-idea:test --no-daemon
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

The implementation changes the Kotlin workspace-files query/result contract,
server validation, IDEA backend enumeration, fake backend, generated protocol,
and raw catalogs. It does not change `SourceIndexFilePolicy` or the source-index
schema. The index-store test that rejects `.kts` remains an explicit gate.

## Change rule

Future work may add file kinds or Gradle Kotlin DSL subtype evidence
additively. It must preserve exact-root containment, generation-bound
exhaustive backend paging, the uncapped internal snapshot, set-valued module
ownership, deterministic public bounds, `.kt`-only source-index authority, and
the rule that incomplete backend evidence cannot prove `INDEX_ONLY`. Any
change that uses filesystem or Git enumeration as candidate authority requires
a superseding ADR.
