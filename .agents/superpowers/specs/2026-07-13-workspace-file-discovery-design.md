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

### Page one generation of project-model candidates and compose the `.kt` index

This is the chosen approach. The Kotlin backend gains deterministic per-module
paging, opaque generation-bound cursors, and a project-model inventory that
includes `.kt` and `.kts`. Rust exhausts one coherent workspace generation,
unions physical paths while preserving all module owners, and joins only `.kt`
candidates to the existing Kotlin source index. Scripts remain
`NOT_APPLICABLE` to that index and become the authoritative input set for
#340's separate Gradle DSL index.

## Architecture

The implementation has three boundaries:

1. `raw/workspace-files` snapshots compiler/project-model `.kt` and `.kts`
   candidates, then pages that exact generation deterministically by backend
   module.
2. `workspace_inventory` exhausts backend pages and reads all exact-root `.kt`
   index rows without applying public filters or limits.
3. `agent workspace-files` validates filters, applies them to one typed
   snapshot, sorts deterministically, enforces the public limit, and projects
   ADR 0020 result views.

The command performs ADR 0019 admission first and passes the admitted exact
root and selected backend to one raw RPC session. It fetches module metadata
and an opaque snapshot token, then echoes that token while paging every module.
Only after the backend snapshot is complete or has typed failures does it read
the exact-root SQLite snapshot and compose records.

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

`snapshotToken` and `pageToken` are opaque server-owned values. Clients may
store and echo them but never decode, construct, or advance them. A page token
is legal only when `includeFiles=true`, a nonblank `snapshotToken`, and one
nonblank `moduleName` are present. The decoded cursor binds the snapshot
generation, exact module identity, and positive next offset. The Kotlin parse
boundary validates only nonblank wire tokens and their legal field
combinations; the backend owns cursor decoding and semantic validation. The
server continues to enforce a positive `maxFilesPerModule` no greater than
`maxResults`.

The backend gathers the complete workspace candidate set in one IDEA read
action, maps paths and roots through exact-root containment, deduplicates, and
sorts a canonical representation containing module identities,
source/content roots, dependencies, and candidate paths. It fingerprints that
representation as the workspace generation returned by the opaque
`snapshotToken`. Before serving an exact-module page, it recomputes the full
inventory and rejects a generation mismatch as
`STALE_WORKSPACE_INVENTORY`. It rejects malformed, out-of-range, or
cross-module cursors as `INVALID_WORKSPACE_FILE_CURSOR`. Only a matching
generation may be sliced at the cursor's offset. The result echoes the same
snapshot token, returns the same `fileCount` on every page, and emits a next
cursor exactly when more sorted paths remain.

Both failures are typed `AnalysisException` subtypes carried by the existing
JSON-RPC error envelope. `STALE_WORKSPACE_INVENTORY` uses conflict status 409
and `retryable=true`; `INVALID_WORKSPACE_FILE_CURSOR` uses status 400 and is not
retryable.

Rust still rejects repeated or non-advancing cursors, inconsistent counts,
overlapping pages, or changed module identity. Equal-cardinality path
replacement and module addition/removal are caught by the generation before
these structural checks. On the first typed stale response, Rust discards the
entire backend attempt and restarts once from fresh metadata. A second stale
response is not retried: Rust discards that backend attempt, marks every module
from the last metadata response partial, emits
`BACKEND_WORKSPACE_INVENTORY_STALE`, and may compose index-only partial
evidence. No page from a stale attempt contributes backend candidates.

`includeFiles=false` returns sorted module metadata and the snapshot token.
The compatibility form `includeFiles=true` without an input snapshot token
returns each requested module's first page and a newly issued top-level token;
any continuation must echo that token. The Rust collector always starts with
metadata and uses exact-module requests bound to its token.

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
linked root without any backend root-module association fails the request as
incomplete project-model evidence. A path outside the canonical workspace or
linked root is rejected before it reaches paging.

The fake backend implements the same generation/fingerprint and opaque-cursor
contract. Contract tests cover root build/settings scripts, a build-logic
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
       metadata.module_path,
       metadata.source_set,
       metadata.package_fq_id,
       packages.fq_name
FROM file_manifest AS manifest
JOIN path_prefixes AS prefixes
  ON prefixes.prefix_id = manifest.prefix_id
LEFT JOIN file_metadata AS metadata
  ON metadata.prefix_id = manifest.prefix_id
 AND metadata.filename = manifest.filename
LEFT JOIN fq_names AS packages
  ON packages.fq_id = metadata.package_fq_id
ORDER BY prefixes.dir_path, manifest.filename
```

The reader verifies the checked-in schema version and required tables. It
decodes `__kast_abs__/` and `__kast_rel__/` through the existing path rules,
rejects non-`.kt` and out-of-root rows with typed evidence, and distinguishes
package states:

```rust
pub(crate) enum WorkspacePackageEvidence {
    Named(WorkspacePackageName),
    Root,
    Unavailable,
    InvalidReference { package_fq_id: i64 },
}
```

No metadata row is `Unavailable`. A present row with null `package_fq_id` is
`Root`. A non-null id with one valid joined name is `Named`. A non-null id
without a joined row is `InvalidReference` and adds
`PACKAGE_METADATA_INVALID`. This avoids using one null for four different
facts.

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
    pub(crate) limitations: BTreeSet<WorkspaceInventoryLimitation>,
}

pub(crate) struct WorkspaceInventoryFile {
    pub(crate) path: WorkspaceFilePath,
    pub(crate) backend_modules: BTreeSet<BackendModuleName>,
    pub(crate) indexed_gradle_modules: BTreeSet<GradleModulePath>,
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
```

One physical path produces one public record. Every backend owner and indexed
Gradle identity is retained in a sorted set. Duplicate backend paths with
different module names are valid. Conflicting facts within the same module
page remain invalid.

`BackendWorkspaceCoverage::Partial` represents a typed workspace-wide failure,
including repeated `STALE_WORKSPACE_INVENTORY` after the single bounded retry.
Every module in that final metadata response is partial and the stale attempt
contributes no backend candidates. Per-module transport or cursor failures use
`Available` with only the affected `BackendModuleCoverage` partial.

`WorkspaceFilePath` contains a proven workspace-relative path and canonical
absolute counterpart. Its constructor rejects absolute relative input, parent
traversal, empty filenames, and paths outside the exact root. Existing paths
are canonicalized to prevent symlink escape. Missing `.kt` index paths remain
lexical evidence only after lexical containment succeeds. Backend source and
content roots receive the same proof before they can associate an index row
with a module.

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
  [--module <exact-module>] \
  [--source-set <exact-source-set>] \
  [--kind source|script] \
  [--package <exact-package>] \
  [--dirty clean|dirty|unknown] \
  [--drift none|filesystem-only|index-only|missing-on-disk|not-applicable|unknown] \
  [--path-prefix <workspace-relative-prefix>] \
  [--glob <workspace-relative-glob>] \
  [--limit <1..=200>] \
  [--fields path,module,source-set,kind,package,index,drift,dirty,evidence | --count] \
  [--verbose | --explain]
```

All filters use AND semantics before the default limit of 20. Module matches
any backend or indexed module owner. Path prefix matches at a segment boundary;
glob matches only normalized relative paths. Missing source-set/package
evidence does not match those filters.

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
      "gradleModules": [":app"],
      "sourceSets": ["main"],
      "kind": "KOTLIN_SOURCE",
      "package": {"state": "NAMED", "name": "app"},
      "indexState": "INDEXED",
      "drift": "NONE",
      "dirtyState": "MODIFIED"
    }
  ],
  "page": {
    "knownMatchCount": 1,
    "returnedCount": 1,
    "truncated": false,
    "inventoryComplete": true,
    "limit": 20
  },
  "limitations": [],
  "schemaVersion": 3
}
```

`knownMatchCount` never poses as a complete total when source coverage is
partial. `--count` groups known counts by kind, index state, drift, and dirty
class without file records. `--verbose` adds module/page/source coverage.
`--explain` adds normalized filters and per-record classification evidence.
The compact high-cardinality fixture remains under 120 lines and 1,500
estimated tokens.

## Capability verification

`AgentPublicCapabilityRoute` maps backend `WORKSPACE_FILES` to
`kast agent workspace-files`. Verification intersects backend read
capabilities with this registry. A Clap contract test resolves every registry
path against `Cli::command()`, so removing or hiding the command breaks the
same proof that authorizes public capability projection. Issue #342 extends
the registry; #338 does not create a parallel catalog.

## Exact-root and testing strategy

The TDD sequence proves:

1. Kotlin query parsing rejects illegal token combinations and blank tokens;
2. opaque cursors reject malformed and cross-module use;
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
    and
14. capability projection requires the real Clap route.

The exact-root regression lives in both
`agent_workspace_files_smoke.rs` and `semantic_workspace_admission_smoke.rs`.
Full gates cover Kotlin, generated contracts, Rust, docs, and rendering.

## Non-goals

This issue does not change the Kotlin source-index schema or admit `.kts` to
`SourceIndexFilePolicy`. It does not recursively search the filesystem, use Git
as candidate authority, classify Gradle task declarations, infer dynamic
Gradle semantics, or expose arbitrary RPC dispatch. Issue #340 owns the Gradle
DSL index and semantic subtype/declaration model; issue #342 owns registry
generalization.
