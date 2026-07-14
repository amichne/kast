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
membership, a build-qualified Gradle project identity when the producer proved
one from the linked Gradle model, source set, and package fully qualified name.
The legacy `file_metadata.module_path` value is an unqualified module label and
is never parsed or exposed as a `GradleProjectPath`. The IDEA producer persists
a set of separate association rows containing `(workspace-relative linked build
root, absolute Gradle project path)` from `GradleModuleData`; an IDEA
module-name fallback remains an unproven label and cannot enter those rows.
Root and included builds may therefore both own `:app` without becoming the
same indexed owner.
`SourceIndexFilePolicy` remains `.kt`-only. A `.kts` record is therefore
`NOT_APPLICABLE` to this Kotlin source index rather than `NOT_INDEXED`; issue
#340 owns a separate Gradle DSL index. An index row alone does not prove current
semantic ownership.

The Rust CLI owns evidence composition. Its internal
`WorkspaceInventorySnapshot` is not subject to public filters or limits. It
retains every candidate returned by all successfully exhausted backend pages
and one generation-stamped Kotlin source-index snapshot, plus per-module and
per-source completeness and limitation evidence. It publishes an exact
composition only after a kind-relevant barrier proves the required backend
generation, source-index generation/progress/pending state, targeted filesystem
evidence, and requested filter/projection evidence did not move during
collection. A source-only or mixed request requires the Kotlin source-index
lane; a script-only request and issue #340 mark that lane irrelevant, do not
read or validate it, and cannot lose exactness because unrelated `.kt` progress
moves. The whole composition retries once when a relevant lane moves; a second
movement is typed partial evidence and can never produce `EXACT` for the
affected kind domain. The independent bounds allow at most two composition
attempts with at most two backend-generation attempts each. Issue #340
consumes the `.kts` candidates but writes Gradle declarations and relationships
to its separate Gradle DSL index. If a
generic backend page transport fails or returns an invalid token, only that
module is partial and backend absence remains unprovable for paths that may
belong to it. A stale workspace generation invalidates the whole backend
attempt: Rust discards it, restarts once from fresh metadata, and returns typed
partial evidence if that
single retry also becomes stale. The partial result carries
`BACKEND_WORKSPACE_INVENTORY_STALE`, marks every module from the last metadata
response incomplete, and retains no backend candidates from either stale
attempt. A typed project-model-incomplete response is also workspace-wide:
Rust discards every backend candidate from that attempt without consuming the
stale retry and preserves the server's typed reason as a public limitation.

## Raw paging and project-model authority

An unpaged `raw/workspace-files` metadata request carries a typed
source-only/script-only/mixed kind domain and lists sorted relevant module
metadata and counts plus an opaque top-level `snapshotToken`. Rust echoes that
kind domain and token on every exact-module request with `includeFiles=true`
and a positive server-bounded `maxFilesPerModule`. The backend fingerprints and
pages only the requested domain, so unrelated source movement cannot invalidate
a script-only snapshot. Snapshot and page handles use the shared
server-held opaque continuation mechanism established for ADR 0020 paging.
Random canonical handles address typed state containing the generation, exact
module, query identity, and next offset; neither clients nor Rust decode or
construct that state.

Each typed store instance uses the same generic mechanism but a distinct token
and state namespace. It has positive typed TTL and capacity limits supplied by
`ServerLimits`, removes expired entries before issue or lookup, and evicts the
oldest expiring entry when capacity is reached. Page handles are single-use;
snapshot leases remain reusable until completion, invalidation, eviction, or
expiry. Canonical-token parsing, unguessable handle lookup, and exact query
identity provide integrity. A malformed, forged, unknown, expired, evicted,
already-consumed, or query-mismatched handle fails as
`INVALID_WORKSPACE_FILE_CURSOR` with only typed `SNAPSHOT_HANDLE` or
`PAGE_HANDLE` failure scope, never stored state. An invalid snapshot lease is
workspace-wide and discards the backend attempt; an invalid page handle is
local to its module only while the snapshot lease still validates.

The generic store owns every issued state and never returns an owning reference
to callers. A typed disposer is invoked exactly once when an entry leaves the
store through expiry, capacity eviction, replacement, query mismatch, explicit
invalidation or completion, terminal single-use consumption, or server
shutdown. Consuming and leased APIs run caller work under store-owned lifetime
control and dispose in `finally` when ownership terminates, including callback
failure. Shutdown drains every typed store; one disposer failure cannot skip
the remaining entries. Stateless continuation families use a no-op disposer,
while ADR 0020 reference/diagnostic state adapts its closeable IDEA traversal
to this owner instead of keeping a parallel lifecycle.

The backend builds a canonical requested-kind inventory in one read action and
fingerprints its sorted module identities, source/content roots, dependencies,
kind domain, and candidate paths. Before serving any exact-module page or final
barrier validation it recomputes the inventory and compares its generation with the
leased snapshot state. Equal-cardinality path replacement, module
addition/removal, or any other inventory change invalidates the lease and fails
with `STALE_WORKSPACE_INVENTORY`. Only server-held state for a matching
generation may be sliced. The backend returns stable `fileCount`,
`returnedFileCount`, and `nextPageToken` values. Rust treats handles as opaque
and validates only that handles do not repeat, physical paths do not overlap,
and cumulative returned evidence never exceeds and finally equals the declared
module count before it marks the module complete.

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
the backend attempt workspace-wide partial and discards its earlier pages.
Generic transport or invalid page-handle failure remains local to the requested
module; invalid snapshot-lease or final-validation failure is workspace-wide.

## Public command contract

The command accepts the standard exact-root `--workspace-root` and `--backend`
flags and the result-view flags introduced by ADR 0020. Its discovery filters
are typed and conjunctive:

- `--module` parses a closed selector: `backend:<exact-name>` or
  `gradle:<workspace-relative-build-root>#<absolute-project-path>`, and matches
  the corresponding backend owner or build-qualified indexed Gradle project;
- `--source-set` matches an exact indexed source set;
- `--kind source|script` distinguishes `.kt` from `.kts`;
- `--package` matches an exact indexed package fully qualified name;
- `--dirty clean|dirty|unknown` filters the typed Git state class;
- `--drift none|filesystem-only|index-only|missing-on-disk|not-applicable|unknown`
  filters cross-source drift;
- `--path-prefix` accepts one normalized workspace-relative path prefix;
- `--glob` accepts one bounded glob over normalized workspace-relative paths;
- `--limit` defaults to 20 and accepts 1 through 200; and
- `--page-token` consumes one opaque continuation returned by the preceding
  invocation of the identical normalized query.

Absolute path prefixes, parent traversal, empty semantic selectors, invalid
package names, regex-prefixed globs, and out-of-range limits fail at the typed
CLI boundary. `--page-token` conflicts with `--count` and requires every other
result-affecting argument to reproduce the original normalized query. Filters
never widen the inventory and are applied before the
public limit. Results sort by normalized workspace-relative path; every
ownership set sorts by its typed module identity. The same evidence snapshot
therefore produces deterministic JSON and TOON.

Public paging is distinct from raw per-module paging. When more known matches
remain after the public limit, Rust registers a
`WorkspaceFilesPublicContinuationState` through the mechanism's dedicated
public workspace-file store and
returns its opaque handle as `nextPageToken`. The state binds the exact root,
backend, normalized filters, selected view/fields, limit, composition-stamp
digest, last emitted relative path, and cumulative returned evidence. A later
invocation consumes that single-use handle, must present the same normalized
query, recollects a coherent snapshot, and must reproduce the bound composition
stamp before seeking strictly after the last path. Filter/view/limit mismatch,
malformed, forged, or unknown handles return
`INVALID_WORKSPACE_FILES_PAGE_TOKEN`; movement in any bound source returns
`STALE_WORKSPACE_FILES_PAGE`. Neither failure silently restarts at page one.
The invalid-token failure is non-retryable status 400 and exposes no stored
state. The stale-token failure is retryable conflict status 409 and requires an
explicit new unpaged query.
Five-hundred-record tests prove 200/200/100 continuation with no gaps or
overlap.

The composition digest canonically serializes the normalized requested-kind
domain, each lane's relevance, and every relevant lane's discriminated
`AVAILABLE(stamp)` or `UNAVAILABLE(reason)` state. It does not require all
lanes to be available. A stable backend-only or index-only partial composition
can therefore page its known matches without pretending to be exact; the token
becomes stale when a bound lane changes stamp, availability, or unavailable
reason. Irrelevant lanes are represented as `IRRELEVANT` and neither movement
nor availability in such a lane invalidates a continuation.

Registration and consumption travel through the internal typed
`raw/workspace-files-continuation` method on the already admitted RPC session.
That method issues state or serializes consumed plain state inside the store's
lifetime-controlled callback, but is not a public agent command or capability.
It does not accept candidate enumeration authority or leak an owning state
reference.

The compact default emits a typed result with the exact workspace root,
bounded file records, ADR 0020's discriminated `EXACT` or `KNOWN_MINIMUM`
cardinality, returned count, truncation, optional next-page token, separate
candidate-inventory and filter-evidence coverage, typed limitations, and schema
version. Each file record includes:

- absolute `filePath` and workspace-relative `relativePath`;
- sorted backend-module and build-qualified indexed-Gradle-project ownership
  sets;
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

The candidate inventory is `COMPLETE` only when every candidate authority
relevant to the requested kind domain could contribute no additional matching
path. Filter evidence is `COMPLETE` only when every requested predicate is
known for every candidate in that domain. Source and script coverage are
tracked separately and conjoined for the overall result whenever the selected
kind domain is mixed; a script-only result and a script count group may be exact
while `.kt` index
progress is incomplete. A complete candidate inventory can therefore coexist
with partial filter evidence:
for example, unavailable Git status makes a `--dirty clean` query inexact even
when all kind-relevant backend and source-index candidates are known.
Cardinality is `EXACT` only when both relevant coverage dimensions are complete;
otherwise it is
`KNOWN_MINIMUM` and counts only matches actually proved. `returnedCount` equals
the emitted file count. `truncated` is true when an exact total exceeds that
count or cardinality is `KNOWN_MINIMUM`, because unseen matches remain possible.
`nextPageToken` is non-null only when another currently known matching record
exists. Coherent partial evidence may continue through all currently known
matches; unstable evidence, or a partial result with no further known match,
remains truncated without a token.

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
and reads one SQLite transaction joining `file_manifest`, `path_prefixes`,
`file_metadata`, the dedicated `file_gradle_projects` association table, and
`fq_names` together with `schema_version.generation`, all
`module_index_progress`, and the count of unapplied `pending_updates`. It never
promotes legacy `file_metadata.module_path` into Gradle identity. It keeps only
`.kt` candidates because that is the source index's declared policy.
Generation increments in the same write transaction as candidate, progress, or
pending-state changes. Source-index coverage is complete only when the
initialized progress set is nonempty, every row is `COMPLETE`, indexed counts
equal totals, and no unapplied update remains. Existing candidates are checked
individually; the implementation does not recurse from the workspace root. Git
porcelain may
annotate a candidate but never adds one absent from backend and index evidence.

Every candidate is normalized against the admitted workspace root. Existing
paths whose canonical target leaves that root and index paths that are
lexically outside it are omitted with a typed limitation. For a missing leaf,
the collector walks upward to the deepest existing ancestor, canonicalizes that
ancestor against the canonical admitted root, and appends only normalized
nonexistent components after containment succeeds. A missing path beneath an
escaping symlink is therefore rejected. A permission error, dangling symlink,
race, or absence of any canonicalizable ancestor yields
`PATH_CONTAINMENT_UNPROVABLE`, excludes the path, and makes candidate coverage
partial. Backend module source/content roots receive the same proof before they
can establish ownership or completeness.

Before collection, the normalized query derives a closed source-only,
script-only, or mixed kind domain and a relevance map for candidate, filter,
and projection evidence. The composition stores each relevant lane as
`AVAILABLE(typed stamp)` or `UNAVAILABLE(typed reason)` and marks non-required
lanes `IRRELEVANT`. After collecting evidence, the barrier validates the
backend snapshot lease or repeats its unavailable classification, re-reads a
relevant source-index generation/progress/pending stamp or unavailable
classification in a fresh transaction, repeats targeted filesystem facts, and
repeats Git only when the selected filter/view/fields require it. Canonical
before/after lane states, including availability tags and reasons, must match.
One changed relevant lane discards the whole attempt and retries once. A second
change emits `CROSS_SOURCE_COMPOSITION_UNSTABLE`, marks candidate and affected
filter coverage partial, suppresses public continuation, and forbids `EXACT`.
Stable
but incomplete index progress or pending updates emits distinct
`SOURCE_INDEX_PROGRESS_INCOMPLETE` or `SOURCE_INDEX_UPDATES_PENDING`
limitations and forbids exact source or mixed candidate coverage, but is
irrelevant to script-only discovery and #340. Mixed/source/script fixtures
prove that exactness and continuation invalidation follow only the selected
kind partitions.

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
status, incomplete index progress, pending index updates, unstable cross-source
composition, unprovable containment, unavailable or invalid package metadata,
and excluded out-of-root rows are distinct typed limitations. A usable
backend-only or index-only snapshot may return partial evidence. Exact-root
admission failure and malformed backend payloads fail closed. When no candidate
authority relevant to the selected kind
domain is usable, the command returns `WORKSPACE_FILE_DISCOVERY_UNAVAILABLE`
instead of a false empty success.

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
  server-held opaque continuation leases, per-module workspace-file paging,
  public continuation state, source/content-root evidence,
  compiler/project-model `.kt`/`.kts` enumeration, and typed project-model
  incompleteness. The shared store owns exact-once disposal, including #337
  closeable IDEA traversal and server shutdown. Generated protocol artifacts
  come from those Kotlin source owners.
- `cli-rs/src/workspace_inventory.rs` and
  `cli-rs/src/workspace_inventory/` own the reusable uncapped inventory,
  generation/progress-aware exact-root Kotlin source-index reader, deepest-
  existing-ancestor containment, targeted filesystem evidence, dirty-state
  annotation, kind-relevant discriminated cross-source composition barrier, and
  internal types.
- `index-store` owns transactional maintenance of the source-index generation,
  module progress, pending-update state, and new `file_gradle_projects`
  build-qualified ownership table. The IDEA producer supplies host-neutral
  identity values; neither owner widens `SourceIndexFilePolicy`.
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
- `cli-rs/AGENTS.md` and `cli-rs/resources/kast-skill/AGENTS.md` own the nearest
  Rust/public-resource boundaries and mandatory package, LSP, and routing gates.
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
./gradlew test --no-daemon
./gradlew buildIdeaPlugin --no-daemon
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-kast-routing-evals.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

The implementation changes the Kotlin workspace-files query/result and shared
continuation contracts, server validation, IDEA backend enumeration, source-
index generation maintenance, fake backend, generated protocol, and raw
catalogs. It adds and versions the `file_gradle_projects` source-index
association table but does not change `SourceIndexFilePolicy`. The index-store
test that rejects `.kts` remains an explicit gate.

## Change rule

Future work may add file kinds or Gradle Kotlin DSL subtype evidence
additively. It must preserve exact-root containment, generation-bound
exhaustive backend paging, the uncapped internal snapshot, set-valued module
ownership, deterministic consumable public bounds, `.kt`-only source-index
authority, deepest-existing-ancestor containment, coherent cross-source stamps,
and the rule that incomplete relevant backend or index evidence cannot prove
`INDEX_ONLY` or `EXACT` for its kind partition. Any
change that uses filesystem or Git enumeration as candidate authority requires
a superseding ADR.
