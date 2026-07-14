# Workspace File Discovery Design

## Goal

Expose `kast agent workspace-files` as a bounded public command that discovers
Kotlin sources and scripts from exact-root semantic evidence, reports Kotlin
source-index and filesystem drift honestly, and supplies an exhaustive
project-model script inventory for the separate Gradle DSL index in issue
#340.

## Current failure

The backend advertises `WORKSPACE_FILES`, and the raw protocol implements
`raw/workspace-files`, but the typed public `kast agent` command tree rejects
`workspace-files`. Agents must discover a hidden raw contract or fall back to
`rg`, `find`, or Git paths.

The raw backend currently caps files per module without a page token. It also
enumerates Kotlin files through module source scope, which does not guarantee
root `build.gradle.kts`, `settings.gradle.kts`, convention-plugin scripts, or
ordinary project scripts. The SQLite source index provides `.kt` manifest,
module, source-set, and package facts, but `SourceIndexFilePolicy` deliberately
rejects `.kts`. That policy remains correct: issue #340 owns a separate Gradle
DSL index rather than mixing Gradle declarations into the Kotlin source index.

Git and module ownership also need stronger boundaries. Porcelain paths are
repository-root-relative, not admitted-workspace-relative. A physical source
may be owned by more than one backend module. Both facts must be represented
before public filtering can be truthful.

## Considered approaches

### Expose the existing capped raw response

This preserves current project-model ownership but cannot prove complete
absence, omits project scripts, and lacks index, package, dirty, and drift
evidence. It would make `INDEX_ONLY` and #340 reuse unreliable.

### Put `.kts` into the Kotlin source index

This would make the existing SQL inventory convenient, but it would erase the
intentional Kotlin-source versus Gradle-DSL boundary. Gradle task, plugin, and
relationship facts need a separate schema and completeness model in #340.
`SourceIndexFilePolicy` therefore stays `.kt`-only.

### Page stable source generations and compose behind one barrier

This is the chosen approach. The Kotlin backend gains deterministic per-module
paging, shared server-held opaque cursors, and a project-model inventory that
includes `.kt` and `.kts`. The raw query carries a typed source-only,
script-only, or mixed domain. Rust exhausts one matching backend generation,
reads the source index with its generation/progress/pending state only for a
domain containing `.kt`, unions physical paths while preserving all module
owners, and joins only `.kt` candidates to the existing Kotlin source index. A
bounded cross-source barrier accepts the composition only when every relevant
lane's discriminated available/unavailable state remains stable. Scripts remain
`NOT_APPLICABLE` to that index and become the authoritative input set for
#340's separate Gradle DSL index.

## Architecture

The implementation has three boundaries:

1. `raw/workspace-files` snapshots the compiler/project-model `.kt`, `.kts`, or
   mixed kind domain requested by its typed selector, then pages that exact
   generation deterministically by backend module.
2. `workspace_inventory` exhausts backend pages, reads all exact-root `.kt`
   index rows plus generation/progress/pending state when source candidates are
   relevant, and proves a stable query-relevant lane barrier without applying
   public filters or limits.
3. `agent workspace-files` validates filters, applies them to one typed
   snapshot, sorts deterministically, enforces the public limit, projects ADR
   0020 result views, and registers a query-bound continuation when known
   matches remain.

The command performs ADR 0019 admission first and passes the admitted exact
root and selected backend to one raw RPC session. It fetches module metadata
and an opaque snapshot token, then echoes that token while paging every module.
Only after the backend snapshot is complete or has typed failures does it read
the exact-root SQLite snapshot when `.kt` candidates are relevant and compose
records. It then revalidates every query-relevant mutable lane; one change
restarts the entire attempt once, while a second change returns typed partial
evidence and never `EXACT` for the affected kind partition.

## Typed raw paging

`WorkspaceFilesQuery` gains `snapshotToken` and `pageToken`.
`WorkspaceFilesResult` returns the snapshot token, and `WorkspaceModule` gains
`returnedFileCount` and `nextPageToken`. The wire contract is:

```kotlin
@Serializable
data class WorkspaceFilesQuery(
    val moduleName: String? = null,
    val includeFiles: Boolean = false,
    val maxFilesPerModule: Int? = null,
    val kindDomain: WorkspaceFileKindDomain = WorkspaceFileKindDomain.MIXED,
    val snapshotToken: String? = null,
    val pageToken: String? = null,
)

@Serializable
data class WorkspaceFilesResult(
    val modules: List<WorkspaceModule>,
    val snapshotToken: String,
    val schemaVersion: Int = SCHEMA_VERSION,
)

@Serializable
data class WorkspaceModule(
    val name: String,
    val sourceRoots: List<String>,
    val contentRoots: List<String> = emptyList(),
    val dependencyModuleNames: List<String>,
    val files: List<String> = emptyList(),
    val filesTruncated: Boolean = false,
    val fileCount: Int,
    val returnedFileCount: Int = files.size,
    val nextPageToken: String? = null,
)
```

`snapshotToken` and `pageToken` are random canonical handles into typed
instances of the shared `ServerHeldContinuationStore`; they contain no
client-decoded offset or generation. The typed store entry binds workspace
generation, exact module, query identity, next offset, issued/expiry time, and
cumulative returned evidence, including the exact kind domain. Clients may
store and echo handles but never
decode, construct, or advance state. A page token is legal only when
`includeFiles=true`, a nonblank snapshot token, and one nonblank module are
present. A snapshot token with `includeFiles=false` and no module is also legal
as final barrier validation.
The Kotlin parse boundary validates canonical token shape and legal field
combinations; lookup owns semantic validation. The server continues to enforce
a positive `maxFilesPerModule` no greater than `maxResults`.

The generic store mechanism is extracted from ADR 0020 reference/diagnostic
paging rather than adding another cursor encoding. Token/state namespaces
remain distinct by type. `ContinuationTtl` and `ContinuationCapacity`
are positive typed `ServerLimits`; tests inject a clock and small capacity.
Expired entries are removed before issue or lookup, capacity evicts the oldest
expiring entry deterministically, page handles are consumed once, and snapshot
leases survive until completion, invalidation, eviction, or expiry. Canonical
UUID parsing, unguessable lookup, and exact query comparison make malformed,
forged, unknown, expired, evicted, consumed, or query-mismatched handles fail as
`INVALID_WORKSPACE_FILE_CURSOR` with typed `details.scope` of
`SNAPSHOT_HANDLE` or `PAGE_HANDLE`, without exposing stored state. Snapshot-
handle failure invalidates the whole backend attempt. Page-handle failure stays
local only after the snapshot lease independently validates.

`ServerHeldContinuationStore<Token, Query, State>` owns entry lifetime through
a typed `ContinuationStateDisposer<State>` and does not return owning state.
Reusable lease access and single-use consumption take callbacks; when ownership
terminates, disposal runs exactly once in `finally`, including callback failure.
Expiry cleanup, deterministic capacity eviction, replacement, query mismatch,
explicit invalidation/completion, terminal consumption, and server shutdown
all remove through the same idempotence-guarded disposal path. Shutdown drains
all entries even if one disposer fails. No-op state uses a no-op disposer; the
#337 reference/diagnostic adapter supplies a disposer for its closeable IDEA
traversal so the generic policy owns that resource too. Fake close-count tests
exercise every exit path plus consume/shutdown and expiry/replacement races,
proving that overlapping triggers never double-close.

The backend gathers the complete requested-kind candidate set in one IDEA read
action, maps paths and roots through exact-root containment, deduplicates, and
sorts a canonical representation containing module identities,
source/content roots, dependencies, and candidate paths. It fingerprints that
representation plus the kind domain as the workspace generation stored behind
the opaque `snapshotToken`. Before serving an exact-module page or final validation
request, it recomputes the full inventory and rejects a generation mismatch as
`STALE_WORKSPACE_INVENTORY`. It rejects missing state, out-of-range stored
offsets, or query/module mismatch as `INVALID_WORKSPACE_FILE_CURSOR`. Only a
matching leased generation may be sliced. The result echoes the same snapshot
token, returns the same `fileCount` on every page, and emits a next handle
exactly when more sorted paths remain.

Both failures are typed `AnalysisException` subtypes carried by the existing
JSON-RPC error envelope. `STALE_WORKSPACE_INVENTORY` uses conflict status 409
and `retryable=true`; `INVALID_WORKSPACE_FILE_CURSOR` uses status 400 and is not
retryable. A third subtype, `WorkspaceProjectModelIncompleteException`, uses
status 503, stable code `WORKSPACE_PROJECT_MODEL_INCOMPLETE`, and
`retryable=true`. Its typed `WorkspaceProjectModelIncompleteReason` is
serialized in `details.reason` as `RUNTIME_INDEXING`,
`PROJECT_MODEL_UNAVAILABLE`, or `LINKED_ROOT_UNASSOCIATED`.

Rust treats tokens as opaque. Its defensive checks are limited to repeated
handles, overlapping physical paths, and cumulative returned evidence that
never exceeds and finally equals the module's declared count. It does not infer
cursor advancement, generation, or module identity from handle bytes.
Equal-cardinality path replacement and module addition/removal are caught by
the server-held generation. On the first typed stale response, Rust discards
the entire backend attempt and restarts once from fresh metadata. A second stale
response is not retried: Rust discards that backend attempt, marks every module
from the last metadata response partial, emits
`BACKEND_WORKSPACE_INVENTORY_STALE`, and may compose index-only partial
evidence. No page from a stale attempt contributes backend candidates.

Project-model incompleteness does not consume the one stale-generation retry.
If metadata fails with the typed code, backend coverage is unavailable. If a
page fails with it after earlier pages succeeded, Rust discards the whole
backend attempt and records workspace-wide partial coverage; no candidate from
that attempt survives. The reason maps one-to-one to
`BACKEND_RUNTIME_INDEXING`, `BACKEND_PROJECT_MODEL_UNAVAILABLE`, or
`BACKEND_LINKED_ROOT_UNASSOCIATED`. Generic transport or invalid-response
failures remain local to the requested module when coherent earlier pages and
the snapshot lease are still valid. Invalid snapshot lease or final-validation
failure is workspace-wide and discards all backend pages from the attempt.

`includeFiles=false` returns sorted module metadata and the snapshot token.
The compatibility form `includeFiles=true` without an input snapshot token
returns each requested module's first page and a newly issued top-level token;
any continuation must echo that token. The Rust collector always starts with
metadata and uses exact-module requests bound to its token.

The materially edited skill-facing workspace-file request, query, and response
contracts move out of `SkillContracts.kt` into matching files. The sealed
response root may retain its direct success and failure variants, while request
and query each own one matching file. This follows the repository's production
Kotlin type-isolation rule without migrating unrelated legacy contracts.

## Project-model `.kts` authority

`backend-idea` introduces `IdeaWorkspaceFileInventory`, with one
`IdeaWorkspaceFileModuleSnapshot` per backend module. It obtains candidates
from IntelliJ project scope, module content/source roots, and linked Gradle
project roots rather than a filesystem walk:

- module/project `FileTypeIndex` supplies compiler-visible `.kt` and `.kts`;
- `ModuleRootManager` content and source roots establish every module owner;
- `GradleSettings.getLinkedProjectsSettings()` establishes directly linked
  roots;
- `GradleProjectSettings.getCompositeBuild()`,
  `GradleProjectSettings.CompositeBuild.getCompositeParticipants()`, and
  `BuildParticipant.getRootPath()` establish included-build roots;
- `GradleModuleDataIndex.findGradleModuleData(Module)`,
  `GradleModuleData.isIncludedBuild()`, `getGradleProjectDir()`, and
  `ModuleData.getLinkedExternalProjectPath()` associate those roots with IDEA
  modules;
- targeted `build.gradle.kts` and `settings.gradle.kts` lookups under those
  model-proven roots admit the root scripts without recursive discovery; and
- project-scope `.kts` candidates cover convention plugins and ordinary
  scripts, then acquire every containing module owner.

Root project scripts use every root module associated with the linked Gradle
project. Included builds retain their linked root and module owners. If a
project-scope script has no content-root owner despite being contained by a
linked Gradle root, the corresponding root-module association owns it. A
linked root without any backend root-module association throws
`WorkspaceProjectModelIncompleteException` with reason
`LINKED_ROOT_UNASSOCIATED`. IDEA dumb/index-not-ready state maps to
`RUNTIME_INDEXING`; unavailable linked Gradle settings or module data maps to
`PROJECT_MODEL_UNAVAILABLE`. A path outside the canonical workspace or linked
root is rejected before it reaches paging.

The same IDEA project-model adapter replaces the source-index producer's
path-derived module guess. For every `.kt` file, it obtains
`GradleModuleDataIndex.findGradleModuleData(module)`, associates the module with
one model-proven direct or composite build root, and reads
`GradleModuleData.getGradlePathOrNull()` as the absolute project path. The
host-neutral `BuildQualifiedGradleProjectIdentity` crossing into `index-store`
contains only a normalized workspace-relative build root and typed Gradle
project path; no IntelliJ type crosses that dependency firewall. The producer
collects every model-proven owner for a file, and the store persists the set in
dedicated `file_gradle_projects` association rows. `module_path` remains a
legacy unqualified label for existing symbol/metrics consumers, and an IDEA
module-name fallback may populate only that legacy field. Workspace discovery
never parses it as Gradle identity. Fixtures place `:app` in both the root build
and an included build and prove they persist, filter, and render as two distinct
owners. Missing or ambiguous model identity produces no build-qualified owner,
not a guessed one.

The fake backend implements the same generation/fingerprint and server-held
cursor contract. Contract tests cover expiry, deterministic capacity eviction,
single-use, forged/unknown/query-mismatched handles, root build/settings
scripts, a build-logic
convention plugin, an ordinary `.kts`, included builds, multiple owners, two
non-overlapping pages, equal-cardinality path replacement, module addition and
removal, cross-module cursors, and invalid tokens.
`SourceIndexFilePolicyTest` continues to prove all `.kts` paths are rejected by
the Kotlin source index.

## Source-index snapshot and package evidence

The read-only SQLite query is uncapped and `.kt`-only:

```sql
SELECT prefixes.dir_path,
       manifest.filename,
       metadata.prefix_id IS NOT NULL AS metadata_present,
       gradle_projects.build_root,
       gradle_projects.project_path,
       metadata.source_set,
       metadata.package_fq_id,
       packages.fq_name
FROM file_manifest AS manifest
JOIN path_prefixes AS prefixes
  ON prefixes.prefix_id = manifest.prefix_id
LEFT JOIN file_metadata AS metadata
  ON metadata.prefix_id = manifest.prefix_id
 AND metadata.filename = manifest.filename
LEFT JOIN file_gradle_projects AS gradle_projects
  ON gradle_projects.prefix_id = manifest.prefix_id
 AND gradle_projects.filename = manifest.filename
LEFT JOIN fq_names AS packages
  ON packages.fq_id = metadata.package_fq_id
ORDER BY prefixes.dir_path, manifest.filename,
         gradle_projects.build_root, gradle_projects.project_path
```

The migration adds `file_gradle_projects(prefix_id, filename, build_root,
project_path)` with all four columns non-null and a composite primary key, then
increments the checked-in source-index schema version. The Rust reader groups
the joined rows into a set and validates both identity components; it does not
select `module_path` for workspace ownership. Store and producer tests prove
multiple owners per file, root/included-build identity, identical project paths
in different builds, malformed identity rejection, legacy IDEA fallback
isolation, migration/reset behavior, and transactional generation change when
an association is added, replaced, or removed.

The same read transaction also selects `schema_version.generation`, every
`module_index_progress` row, and the count of unapplied `pending_updates`. The
reader verifies the checked-in schema version and required tables. It decodes
`__kast_abs__/` and `__kast_rel__/` through the existing path rules, rejects
non-`.kt` and out-of-root rows with typed evidence, and distinguishes package
states:

```rust
pub(crate) enum WorkspacePackageEvidence {
    Named(WorkspacePackageName),
    Root,
    Unavailable,
    InvalidReference { package_fq_id: i64 },
}
```

Indexed Gradle ownership is a structural tuple, never a renamed string:

```rust
pub(crate) struct BuildQualifiedGradleProjectIdentity {
    build_root: WorkspaceRelativeGradleBuildRoot,
    project_path: GradleProjectPath,
}
```

`GradleProjectPath` accepts only Gradle absolute project-path syntax. The build
root is `.` for the admitted root build or a normalized contained relative path
for an included build. Both components must be present before the identity can
be constructed.

No metadata row is `Unavailable`. A present row with null `package_fq_id` is
`Root`. A non-null id with one valid joined name is `Named`. A non-null id
without a joined row is `InvalidReference` and adds
`PACKAGE_METADATA_INVALID`. This avoids using one null for four different
facts.

```rust
pub(crate) struct SourceIndexSnapshotStamp {
    generation: SourceIndexGeneration,
    progress: BTreeMap<SourceIndexModule, SourceIndexModuleProgress>,
    unapplied_updates: SourceIndexPendingCount,
}

pub(crate) enum SourceIndexModuleProgress {
    Complete { indexed: SourceIndexFileCount, total: SourceIndexFileCount },
    Pending { indexed: SourceIndexFileCount, total: SourceIndexFileCount },
    Indexing { indexed: SourceIndexFileCount, total: SourceIndexFileCount },
    Failed,
}
```

The existing generation column becomes an enforced change token: every write
transaction that mutates candidate tables, progress, or pending applied state
increments it before commit. Candidate authority is complete only when every
row in the nonempty progress set initialized for the current index run is
`Complete`, indexed equals total, and unapplied updates are zero. An empty
progress set, `Pending`, `Indexing`, `Failed`, count mismatch, or pending updates
is typed partial source coverage even when the row set is readable; it is not
consulted for a script-only domain.

## Internal inventory model

The collector exposes source completeness and ownership as types:

```rust
pub(crate) struct WorkspaceInventorySnapshot {
    pub(crate) workspace_root: WorkspaceRoot,
    pub(crate) modules: BTreeMap<BackendModuleName, WorkspaceInventoryModule>,
    pub(crate) files: Vec<WorkspaceInventoryFile>,
    pub(crate) backend_coverage: BackendWorkspaceCoverage,
    pub(crate) index_coverage: IndexWorkspaceCoverage,
    pub(crate) dirty_coverage: DirtyWorkspaceCoverage,
    pub(crate) composition: WorkspaceCompositionEvidence,
    pub(crate) limitations: BTreeSet<WorkspaceInventoryLimitation>,
}

pub(crate) struct WorkspaceInventoryFile {
    pub(crate) path: WorkspaceFilePath,
    pub(crate) backend_modules: BTreeSet<BackendModuleName>,
    pub(crate) indexed_gradle_projects: BTreeSet<BuildQualifiedGradleProjectIdentity>,
    pub(crate) source_sets: BTreeSet<WorkspaceSourceSet>,
    pub(crate) kind: WorkspaceFileKind,
    pub(crate) package: WorkspacePackageEvidence,
    pub(crate) index_state: WorkspaceFileIndexState,
    pub(crate) drift: WorkspaceFileDrift,
    pub(crate) dirty_state: WorkspaceFileDirtyState,
    pub(crate) evidence: BTreeSet<WorkspaceEvidenceSource>,
}

pub(crate) enum BackendWorkspaceCoverage {
    Available {
        modules: BTreeMap<BackendModuleName, BackendModuleCoverage>,
    },
    Partial {
        code: WorkspaceInventoryLimitationCode,
        modules: BTreeMap<BackendModuleName, BackendModuleCoverage>,
    },
    Unavailable {
        code: WorkspaceInventoryLimitationCode,
    },
}

pub(crate) enum BackendModuleCoverage {
    Complete,
    Partial {
        code: WorkspaceInventoryLimitationCode,
        returned_count: usize,
        expected_count: usize,
    },
}

pub(crate) enum WorkspaceInventoryLimitationCode {
    BackendCapabilityUnavailable,
    BackendMetadataUnavailable,
    BackendPageUnavailable,
    BackendWorkspaceInventoryStale,
    BackendRuntimeIndexing,
    BackendProjectModelUnavailable,
    BackendLinkedRootUnassociated,
    SourceIndexUnavailable,
    SourceIndexIncompatible,
    SourceIndexProgressIncomplete,
    SourceIndexUpdatesPending,
    DirtyStateUnavailable,
    CrossSourceCompositionUnstable,
    PathContainmentUnprovable,
    PackageMetadataInvalid,
    ProjectModelOwnershipUnknown,
    OutsideWorkspaceExcluded,
}

pub(crate) struct WorkspaceCompositionEvidence {
    kind_domain: WorkspaceRequestedKindDomain,
    backend: WorkspaceLaneEvidence<BackendWorkspaceGeneration>,
    source_index: WorkspaceLaneEvidence<SourceIndexSnapshotStamp>,
    filesystem: WorkspaceLaneEvidence<WorkspaceFilesystemFingerprint>,
    git: WorkspaceLaneEvidence<WorkspaceGitFingerprint>,
    state: WorkspaceCompositionState,
}

pub(crate) enum WorkspaceLaneEvidence<Stamp> {
    Relevant {
        purpose: BTreeSet<WorkspaceLanePurpose>,
        stamp: WorkspaceLaneStamp<Stamp>,
    },
    Irrelevant,
}

pub(crate) enum WorkspaceLaneStamp<Stamp> {
    Available(Stamp),
    Unavailable(WorkspaceLaneUnavailableReason),
}

pub(crate) enum WorkspaceRequestedKindDomain {
    KotlinSourceOnly,
    KotlinScriptOnly,
    SourceAndScript,
}

pub(crate) enum WorkspaceLanePurpose {
    CandidateAuthority,
    FilterEvidence,
    ProjectionEvidence,
}

pub(crate) enum WorkspaceCompositionState {
    Coherent,
    Partial { changed: BTreeSet<WorkspaceEvidenceLane> },
}

pub(crate) struct WorkspaceMatchCoverage {
    source: WorkspaceKindMatchCoverage,
    script: WorkspaceKindMatchCoverage,
}

pub(crate) struct WorkspaceKindMatchCoverage {
    candidate_inventory: WorkspaceCoverageState,
    filter_evidence: WorkspaceCoverageState,
}

pub(crate) enum WorkspaceCoverageState {
    Complete,
    Partial,
}
```

One physical path produces one public record. Every backend owner and
build-qualified indexed Gradle identity is retained in a sorted set. Duplicate
backend paths with different module names are valid. Conflicting facts within
the same module page remain invalid.

The composition digest uses canonical serialization of `kind_domain`, every
lane's `Relevant` or `Irrelevant` tag, the purpose set, and the exact
`Available(stamp)` or `Unavailable(reason)` value. This makes stable
backend-only and index-only partial compositions representable and pageable.
A continuation becomes stale if a relevant lane changes value, availability,
or unavailable reason. An irrelevant lane is neither read nor compared.

`BackendWorkspaceCoverage::Partial` represents a typed workspace-wide failure,
including repeated `STALE_WORKSPACE_INVENTORY` after the single bounded retry.
Every module in that final metadata response is partial and the stale attempt
contributes no backend candidates. Per-module transport or cursor failures use
`Available` with only the affected `BackendModuleCoverage` partial. A typed
project-model failure during paging also uses workspace-wide `Partial`,
discards all pages from that attempt, and carries its exact mapped limitation.
A typed project-model failure before metadata uses `Unavailable` with the same
reason.

`WorkspaceFilePath` contains a proven workspace-relative path and canonical
absolute counterpart. Its constructor rejects absolute relative input, parent
traversal, empty filenames, and paths outside the exact root. Existing paths
are canonicalized to prevent symlink escape. For a missing leaf, it walks to
the deepest existing ancestor, canonicalizes that ancestor against the
canonical root, and appends only normalized nonexistent components after the
proof succeeds. A missing path beneath an escaping symlink is rejected. A
dangling symlink, permission failure, race, or absent canonicalizable ancestor
becomes `PATH_CONTAINMENT_UNPROVABLE`, excludes that candidate, and makes
candidate coverage partial. Backend source and content roots receive the same
proof before they can associate an index row with a module.

## Cross-source composition barrier

The normalized query first derives `WorkspaceRequestedKindDomain` and lane
purposes. Backend and targeted filesystem evidence are candidate-relevant for
every domain. The Kotlin source index is candidate-relevant only for
`KotlinSourceOnly` and `SourceAndScript`; it is `Irrelevant` for
`KotlinScriptOnly` and #340. Git is filter/projection-relevant only when dirty
selection, grouping, or projected fields require it. Package/source-set/index
evidence is similarly relevant only to source records and selected predicates
or fields. The raw backend request receives the same kind domain, so its
generation and failure coverage cannot be polluted by the excluded file kind.

One composition attempt records each relevant lane as `Available(typed stamp)`
or `Unavailable(typed reason)`, captures canonical existence/containment facts
for exactly the candidate paths, and skips irrelevant reads. It then validates
the backend lease or repeats backend unavailability, re-reads a relevant index
stamp or unavailable classification in a fresh transaction, repeats targeted
filesystem facts, and repeats Git only when relevant. No directory walk is
introduced.

Identical before/after canonical lane evidence produces `Coherent`, including
stable unavailable lanes. Any changed relevant lane discards the whole attempt
and retries once. If the second attempt also moves, the result
carries `CROSS_SOURCE_COMPOSITION_UNSTABLE`, marks candidate and affected
filter coverage partial, suppresses public continuation, and cannot emit
`EXACT`; cross-source drift and absence classifications become `UNKNOWN`.
Stable incomplete source-index progress or pending updates do not spin: they
produce `SOURCE_INDEX_PROGRESS_INCOMPLETE` or
`SOURCE_INDEX_UPDATES_PENDING`, retain proven rows, and keep candidate coverage
partial only for source and mixed domains. Script-only and #340 remain exact
when their relevant lanes are complete. Mutation fixtures change backend
generation, source-index generation, progress/pending state, filesystem
existence/symlink resolution, and Git status between barrier reads to prove one
retry and the terminal partial state. Availability-transition fixtures change
available to unavailable, unavailable to available, and unavailable reasons.
Mixed/source/script tests prove unrelated `.kt` progress neither retries nor
invalidates script-only continuation, while mixed and source-only queries do.
In mixed count output the script group can remain `Exact` while the source
group and overall result are `KnownMinimum`. The separate budgets permit at
most two composition attempts, each with at most two backend-generation
attempts; call-count tests enforce the four-attempt ceiling.

The coarse file kind remains:

```rust
pub(crate) enum WorkspaceFileKind {
    KotlinSource,
    KotlinScript,
}
```

Issue #340 adds a separate Gradle DSL classification and index for project,
settings, convention-plugin, and ordinary scripts. It consumes
`KotlinScript` candidates and their backend ownership/coverage; it does not
write `.kts` rows into the Kotlin source index.

## Candidate and drift composition

Backend and index paths are keyed by `WorkspaceFilePath`. Filesystem and Git
never add candidates. `.kt` index state and drift follow this table:

- backend and index present: `INDEXED`, `NONE`;
- backend present and readable index absent: `NOT_INDEXED`,
  `FILESYSTEM_ONLY`;
- index present and every possible backend owner complete: `INDEXED`,
  `INDEX_ONLY`;
- index present with any possible owner partial/unavailable: `INDEXED`,
  `UNKNOWN`;
- missing candidate: `MISSING_ON_DISK` with independent index state; and
- index unavailable for a backend source: `UNKNOWN`, `UNKNOWN`.

`.kts` is always `NOT_APPLICABLE` to the Kotlin source index. A present script
has `NOT_APPLICABLE` drift; a missing script is `MISSING_ON_DISK`. #340 later
adds independent Gradle-index state rather than changing these meanings.

Potential ownership is conservative. Exact backend owners are used first.
For an index-only `.kt`, canonical source/content-root containment may
associate multiple modules. `INDEX_ONLY` requires every associated module to
be complete. If association is empty or ambiguous while any module is partial,
drift is `UNKNOWN` and the result carries
`PROJECT_MODEL_OWNERSHIP_UNKNOWN`.

## Exact-root Git dirty state

The adapter runs:

```console
git -C <workspace-root> rev-parse --show-toplevel
git -c status.relativePaths=false -C <workspace-root> status --porcelain=v2 -z --untracked-files=all -- .
```

It canonicalizes the Git top level and proves the admitted workspace root is
contained. The explicit config override makes porcelain current and original
rename paths repository-root-relative regardless of repository configuration.
Those paths are normalized, restricted to the exact workspace prefix, and then
relativized to the workspace. A rename annotates each contained candidate
endpoint independently; crossing the workspace boundary never imports the
outside endpoint. Invalid UTF-8, containment failure, or Git failure makes all
candidate dirty states `UNKNOWN` with `DIRTY_STATE_UNAVAILABLE`. Only a
successfully mapped snapshot makes an absent candidate `CLEAN`.

Detailed dirty states are `CLEAN`, `MODIFIED`, `ADDED`, `DELETED`, `RENAMED`,
`UNTRACKED`, `CONFLICTED`, and `UNKNOWN`. The public filter collapses them into
clean, dirty, or unknown.

## Public arguments and output

The stable syntax is:

```console
kast agent workspace-files \
  --workspace-root <repo> \
  [--backend idea|headless] \
  [--module backend:<exact-name>|gradle:<build-root>#<project-path>] \
  [--source-set <exact-source-set>] \
  [--kind source|script] \
  [--package <exact-package>] \
  [--dirty clean|dirty|unknown] \
  [--drift none|filesystem-only|index-only|missing-on-disk|not-applicable|unknown] \
  [--path-prefix <workspace-relative-prefix>] \
  [--glob <workspace-relative-glob>] \
  [--limit <1..=200>] \
  [--page-token <opaque>] \
  [--fields path,module,source-set,kind,package,index,drift,dirty,evidence | --count] \
  [--verbose | --explain]
```

All filters use AND semantics before the default limit of 20. Module parses a
closed backend or build-qualified Gradle selector; `gradle:.#:app` identifies
`:app` in the root build while `gradle:included/tools#:app` is distinct. Path
prefix matches at a segment boundary;
glob matches only normalized relative paths. Missing source-set/package
evidence does not match those filters. `--page-token` conflicts with `--count`;
all other result-affecting arguments must reproduce the original normalized
query exactly.

The public page token is not a raw module cursor. After a coherent composition,
Rust registers typed `WorkspaceFilesPublicContinuationState` in the shared
mechanism's dedicated public workspace-file store whenever another known
matching path exists. The state binds
the exact root, backend, normalized filters, view/field selection, limit,
composition-stamp digest, last emitted relative path, and cumulative returned
evidence. A resumed invocation consumes the handle, requires the identical
normalized query, recollects a coherent snapshot, compares its stamp digest,
then seeks strictly after the bound path. Query mismatch, malformed, forged, or
unknown state is `INVALID_WORKSPACE_FILES_PAGE_TOKEN`; source movement is
`STALE_WORKSPACE_FILES_PAGE`. Both are typed failures rather than page-one
fallback.

The digest includes the normalized kind domain, relevance/purpose of each lane,
and each relevant lane's exact available stamp or unavailable reason. Stable
backend-only and index-only partial compositions may therefore continue over
known matches while preserving `KNOWN_MINIMUM`; a lane availability transition
or reason change is stale. Script-only tokens contain an irrelevant index lane
and survive unrelated `.kt` generation/progress/pending movement.

`INVALID_WORKSPACE_FILES_PAGE_TOKEN` is non-retryable status 400 and preserves
only the stable code plus failure class, never stored state.
`STALE_WORKSPACE_FILES_PAGE` is retryable conflict status 409 and tells the
caller to begin a new unpaged query explicitly.

Issue and consume use internal `raw/workspace-files-continuation` over the
already admitted session. It stores query/stamp/seek/cumulative state only and
does not enumerate candidates or become a public capability.

The compact result uses one physical-file record:

```json
{
  "type": "KAST_AGENT_WORKSPACE_FILES_RESULT",
  "ok": true,
  "workspaceRoot": "/repo",
  "files": [
    {
      "filePath": "/repo/app/src/main/kotlin/app/App.kt",
      "relativePath": "app/src/main/kotlin/app/App.kt",
      "backendModules": ["app.main"],
      "gradleProjects": [
        {"buildRoot": ".", "projectPath": ":app"}
      ],
      "sourceSets": ["main"],
      "kind": "KOTLIN_SOURCE",
      "package": {"state": "NAMED", "name": "app"},
      "indexState": "INDEXED",
      "drift": "NONE",
      "dirtyState": "MODIFIED"
    }
  ],
  "page": {
    "cardinality": {"type": "EXACT", "totalCount": 1},
    "returnedCount": 1,
    "truncated": false,
    "coverage": {
      "candidateInventory": "COMPLETE",
      "filterEvidence": "COMPLETE"
    },
    "limit": 20,
    "nextPageToken": null
  },
  "limitations": [],
  "schemaVersion": 3
}
```

The page reuses ADR 0020's `AgentResultCardinality`. `EXACT.totalCount` is legal
only when candidate inventory is exhaustive for the selected kind domain and
every selected predicate is known for every candidate in that domain.
Source/script coverage is computed separately and only the relevant partitions
are conjoined. Otherwise the page emits
`KNOWN_MINIMUM.knownMinimumCount` for matches actually proved. Candidate and
filter coverage remain separate because they answer different questions. For
example, complete backend/index candidates plus unavailable Git status produce
`candidateInventory=COMPLETE`, `filterEvidence=PARTIAL`, and `KNOWN_MINIMUM`
for `--dirty clean`; without a dirty filter, that same Git limitation does not
make match cardinality inexact.

For `--kind script`, unavailable or moving `.kt` index state is irrelevant and
does not prevent exactness. A source-only request requires complete source-index
authority; a mixed request requires both source and script partitions for an
exact overall count. Count groups retain their partitioned cardinality, so a
mixed result may report an exact script group beside a known-minimum source
group and known-minimum overall cardinality.

`returnedCount` equals the emitted file records. `truncated` is true when an
exact total exceeds returned count or cardinality is `KNOWN_MINIMUM`, because
unseen matches remain possible. `--count` groups known counts by kind, index
state, drift, and dirty class without file records; each group uses the same
discriminated cardinality rather than an unqualified integer. `--verbose` adds
module/page/source coverage. `--explain` adds normalized filters and per-record
classification evidence. The compact high-cardinality fixture remains under
120 lines and 1,500 estimated tokens.

## Capability verification

`AgentPublicCapabilityRoute` maps backend `WORKSPACE_FILES` to
`kast agent workspace-files`. Verification intersects backend read
capabilities with this registry. A Clap contract test resolves every registry
path against `Cli::command()`, so removing or hiding the command breaks the
same proof that authorizes public capability projection. Issue #342 extends
the registry; #338 does not create a parallel catalog.

The internal raw catalog has a separate, explicit source boundary:
`cli-rs/resources/kast-skill/references/commands.json` is hand-authored and is
updated with snapshot/page fields and current internal guidance. The release
contract generator also records the internal continuation issue/consume method.
It consumes that JSON to produce `commands.yaml`, request schemas, and request
samples; it never generates the JSON source.

## Exact-root and testing strategy

The TDD sequence proves:

1. Kotlin query parsing rejects illegal token combinations and blank tokens;
2. the shared server-held store rejects malformed, forged, unknown, expired,
   evicted, consumed, and query/cross-module-mismatched handles and disposes
   owned state exactly once on every removal path and server shutdown;
3. equal-cardinality path replacement and module addition/removal return
   `STALE_WORKSPACE_INVENTORY`;
4. Rust discards a stale attempt, restarts exactly once, and returns typed
   partial evidence without stale backend candidates after a second mismatch;
5. fake and IDEA backends return stable non-overlapping pages and include root,
   settings, included-build, convention-plugin, and ordinary scripts;
6. `.kts` remains rejected by `SourceIndexFilePolicy`;
7. the index query distinguishes absent metadata, root package, named package,
   and dangling package ids;
8. shared physical files retain multiple module owners;
9. partial paging never produces `INDEX_ONLY`;
10. nested Git roots and in/out rename endpoints map correctly;
11. root A with only root B's descriptor is rejected before any
    `raw/workspace-files` request or root B index read;
12. filters, output views, limitations, deterministic order, and budgets hold;
13. returned `filePath` composes directly with diagnostics and symbol hinting;
14. `EXACT` versus `KNOWN_MINIMUM` follows both candidate and filter-evidence
    coverage, including unavailable Git/package evidence;
15. unassociated roots, runtime indexing, unavailable project models, and
    metadata failures retain distinct typed limitations without stale backend
    candidates; and
16. capability projection requires the real Clap route;
17. deepest-existing-ancestor containment admits a missing in-root leaf but
    excludes escaping/dangling symlink and unprovable cases with typed evidence;
18. source-index generation, progress, and pending state plus backend,
    filesystem, and Git mutations exercise the single composition retry and
    prove unstable/incomplete relevant evidence never emits `EXACT`; and
19. public continuation returns 500 filtered records as 200/200/100 without
    overlap and rejects stale, forged/unknown, and filter-mismatched tokens;
20. build-qualified producer/storage fixtures distinguish the root build's
    `:app` from an included build's `:app` and never expose an IDEA fallback as
    Gradle identity; and
21. mixed/source/script fixtures prove source-index progress is relevant only
    to a selected source partition, including continuation digests and grouped
    cardinality.

The exact-root regression lives in both
`agent_workspace_files_smoke.rs` and `semantic_workspace_admission_smoke.rs`.
Full gates cover Kotlin, generated contracts, Rust, docs, and rendering. Final
acceptance also runs `./gradlew test` and `./gradlew buildIdeaPlugin` so the
cross-module schema/producer contract and packaged IDEA plugin are proved as a
whole.

`analysis-api/AGENTS.md` records the wire, exact-once continuation disposal,
and generated-contract boundary. `backend-idea/AGENTS.md` records project-model
inventory, Gradle bridge, build-qualified source-index producer, typed
incompleteness, and paging gates. `index-store/AGENTS.md` owns the association
table, generation, and legacy-label prohibition. The new Rust inventory
directory owns its own scoped guide. `cli-rs/AGENTS.md` and
`cli-rs/resources/kast-skill/AGENTS.md` record the public command, continuation,
catalog/package ownership, and mandatory package, LSP, and routing gates. These
guides change with their new source boundaries rather than leaving ownership
only in this design.

## Non-goals

This issue changes the Kotlin source-index schema only to add the
`file_gradle_projects` build-qualified ownership association table and advance
its checked-in version; it does not admit `.kts` to `SourceIndexFilePolicy` or
reinterpret legacy
`module_path`. It does not recursively search the filesystem, use Git
as candidate authority, classify Gradle task declarations, infer dynamic
Gradle semantics, or expose arbitrary RPC dispatch. Issue #340 owns the Gradle
DSL index and semantic subtype/declaration model; issue #342 owns registry
generalization.
