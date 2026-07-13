# Workspace File Discovery Design

## Goal

Expose `kast agent workspace-files` as a bounded public command that discovers
Kotlin sources and scripts from semantic workspace evidence, reports index and
filesystem drift honestly, and supplies a reusable uncapped inventory for the
Gradle Kotlin DSL work in issue #340.

## Current failure

The backend advertises `WORKSPACE_FILES`, and the raw protocol implements
`raw/workspace-files`, but the typed public `kast agent` command tree rejects
`workspace-files`. Agents must either discover the hidden raw contract or fall
back to `rg`, `find`, or Git paths. The existing raw result also lacks source
set, package, index state, dirty state, and cross-source drift.

The raw backend caps file paths per module. The source index contains
`file_manifest`, `file_metadata`, `path_prefixes`, and `fq_names`, but index
rows can be stale or belong to nested linked worktrees. Neither source is a
complete public result by itself.

## Considered approaches

### Backend-only projection

The smallest change would expose `raw/workspace-files` through a typed Clap
command. This preserves project-model ownership but cannot satisfy package,
source-set, indexed-state, dirty-state, or index-drift requirements. The
per-module cap also becomes easy to misread as complete discovery.

### Source-index-only query

A direct SQLite command could provide module, source set, package, and manifest
state without a daemon round trip. It would incorrectly treat every retained
manifest row as current semantic ownership. The live index can contain nested
worktree paths, and absence from a cache cannot establish current project-model
state.

### Typed hybrid inventory

The chosen approach joins backend ownership and source-index facts by exact
normalized path, then adds only targeted filesystem and Git annotations. It
preserves each source's authority, exposes uncertainty as types, and creates a
Rust inventory that #340 can reuse. It requires more composition code, but it
is the only approach that meets the acceptance criteria without changing the
Kotlin protocol.

## Architecture

The implementation has two boundaries:

1. `workspace_inventory` collects all known candidates and source coverage.
   It has no public result limit and applies no user filters.
2. `agent workspace-files` validates typed filters, applies them to the
   snapshot, sorts deterministically, enforces the public limit, and projects
   the ADR 0020 result views.

The command first performs ADR 0019 exact-root admission and opens one selected
runtime session. It requests `raw/workspace-files` with `includeFiles=true`
and no smaller CLI-owned per-module cap. In parallel with composition (not with
shared mutation), it opens the configured source-index database read-only and
reads all Kotlin manifest rows in one SQLite snapshot. The collector merges
those sources after normalizing every path to the admitted root.

The backend response already reports module name, source roots, dependency
module names, file count, returned files, and `filesTruncated`. No Kotlin wire
change is required. The index query is:

```sql
SELECT prefixes.dir_path,
       manifest.filename,
       metadata.module_path,
       metadata.source_set,
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

The reader verifies the checked-in source-index schema version and required
tables before querying. It decodes the existing `__kast_abs__/` and
`__kast_rel__/` prefixes through the same path rules as current Rust
source-index readers. It discards non-`.kt`/`.kts` rows and out-of-root paths
with typed limitation evidence.

## Internal type model

The collector exposes these responsibilities as types rather than boolean
combinations:

```rust
pub(crate) struct WorkspaceInventorySnapshot {
    pub(crate) workspace_root: WorkspaceRoot,
    pub(crate) modules: Vec<WorkspaceInventoryModule>,
    pub(crate) files: Vec<WorkspaceInventoryFile>,
    pub(crate) backend_coverage: BackendWorkspaceCoverage,
    pub(crate) index_coverage: IndexWorkspaceCoverage,
    pub(crate) dirty_coverage: DirtyWorkspaceCoverage,
    pub(crate) limitations: BTreeSet<WorkspaceInventoryLimitation>,
}

pub(crate) struct WorkspaceInventoryFile {
    pub(crate) path: WorkspaceFilePath,
    pub(crate) module: WorkspaceModuleEvidence,
    pub(crate) source_set: Option<WorkspaceSourceSet>,
    pub(crate) kind: WorkspaceFileKind,
    pub(crate) package_name: Option<WorkspacePackageName>,
    pub(crate) index_state: WorkspaceFileIndexState,
    pub(crate) drift: WorkspaceFileDrift,
    pub(crate) dirty_state: WorkspaceFileDirtyState,
    pub(crate) evidence: BTreeSet<WorkspaceEvidenceSource>,
}
```

`WorkspaceFilePath` contains a proven workspace-relative path and its absolute
counterpart. Its constructor rejects absolute relative input, parent
traversal, an empty filename, and paths outside the exact root. Existing paths
are canonicalized to prevent a symlink target from escaping the root. Missing
index-only paths remain lexical evidence and can be emitted only after lexical
root containment succeeds.

`WorkspaceModuleEvidence` preserves both optional backend module name and
optional indexed Gradle module path. The public `--module` filter matches
either exact value, while output retains both so an IDEA display name cannot
silently replace Gradle identity. `WorkspaceSourceSet` and
`WorkspacePackageName` are validated non-empty newtypes. Package validation
accepts Kotlin identifier segments, including backticked segments, separated
by dots.

The file kind is intentionally coarse in #338:

```rust
pub(crate) enum WorkspaceFileKind {
    KotlinSource,
    KotlinScript,
}
```

Issue #340 adds an independent Gradle script subtype such as project,
settings, convention plugin, or ordinary Kotlin script. It consumes the
uncapped inventory and project-model evidence; #338 does not guess those
subtypes from a filename and claim Gradle semantics prematurely.

Coverage is explicit:

```rust
pub(crate) enum BackendWorkspaceCoverage {
    Complete,
    Truncated { module_names: Vec<BackendModuleName> },
    Unavailable { code: WorkspaceInventoryLimitationCode },
}

pub(crate) enum IndexWorkspaceCoverage {
    Available,
    Unavailable { code: WorkspaceInventoryLimitationCode },
}

pub(crate) enum DirtyWorkspaceCoverage {
    Available,
    Unavailable { code: WorkspaceInventoryLimitationCode },
}
```

The source-index query is uncapped. `WorkspaceInventorySnapshot::files` keeps
all candidates supplied by the available sources. Backend truncation remains
visible in `backend_coverage`; "uncapped internal" never means pretending an
upstream capped response was exhaustive.

## Candidate and drift composition

Backend paths and index paths are keyed by `WorkspaceRelativePath`. The
collector does not add paths from filesystem or Git enumeration. It calls
`symlink_metadata` only for an existing candidate, and the Git porcelain v2
snapshot only annotates candidate paths.

Index state is independent of drift:

- `INDEXED` means the exact path is in the readable manifest snapshot.
- `NOT_INDEXED` means a backend-owned path is absent from a readable manifest.
- `UNKNOWN` means index evidence is unavailable.

Drift follows the ADR 0021 truth table. A backend-owned path with no manifest
row is `FILESYSTEM_ONLY` when it exists. A manifest path absent from backend is
`INDEX_ONLY` only when backend coverage proves exhaustive absence. A missing
candidate is `MISSING_ON_DISK`. Any unprovable absence is `UNKNOWN`.

Backend completeness is evaluated per module before a global result is
claimed. If any module is truncated and an index row cannot be associated with
a distinct complete backend module, the row remains `UNKNOWN`. This
conservative rule is more important than maximizing `INDEX_ONLY` counts.

Detailed dirty states are `CLEAN`, `MODIFIED`, `ADDED`, `DELETED`, `RENAMED`,
`UNTRACKED`, `CONFLICTED`, and `UNKNOWN`. The CLI filter collapses them into
clean, dirty, or unknown; structured output preserves the detailed state. If
the root is not a Git worktree or porcelain parsing fails, every candidate is
`UNKNOWN` and the snapshot contains `DIRTY_STATE_UNAVAILABLE`.

## Public arguments and filtering

The new `AgentWorkspaceFilesArgs` contains `AgentRuntimeArgs`, ADR 0020's
`AgentResultViewArgs`, and typed filters. The stable syntax is:

```console
kast agent workspace-files \
  --workspace-root <repo> \
  [--backend idea|headless] \
  [--module <exact-module>] \
  [--source-set <exact-source-set>] \
  [--kind source|script] \
  [--package <exact-package>] \
  [--dirty clean|dirty|unknown] \
  [--drift none|filesystem-only|index-only|missing-on-disk|unknown] \
  [--path-prefix <workspace-relative-prefix>] \
  [--glob <workspace-relative-glob>] \
  [--limit <1..=200>] \
  [--fields path,module,source-set,kind,package,index,drift,dirty,evidence | --count] \
  [--verbose | --explain]
```

The default limit is 20. All filters use AND semantics and run before the
limit. `--glob` matches only normalized relative paths and rejects the
`regex:` prefix. `--path-prefix` matches a path exactly or at a segment
boundary, never as a raw string prefix. Missing source-set/package evidence
does not match a requested source-set/package filter.

## Output and limitations

The compact result shape is:

```json
{
  "type": "KAST_AGENT_WORKSPACE_FILES_RESULT",
  "ok": true,
  "workspaceRoot": "/repo",
  "files": [
    {
      "filePath": "/repo/app/src/main/kotlin/app/App.kt",
      "relativePath": "app/src/main/kotlin/app/App.kt",
      "module": {"backendName": "app.main", "gradlePath": ":app"},
      "sourceSet": "main",
      "kind": "KOTLIN_SOURCE",
      "packageName": "app",
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
partial. `inventoryComplete=false` and typed limitations carry that fact.
`--count` returns known counts grouped by kind, index state, drift, and dirty
class without file records. `--verbose` adds module/source coverage and
evidence sources. `--explain` additionally echoes the normalized typed query
and explains which source established each classification.

Limitations have stable codes and structured affected-module/path counts:

- `BACKEND_WORKSPACE_FILES_UNAVAILABLE`;
- `BACKEND_ENUMERATION_TRUNCATED`;
- `SOURCE_INDEX_UNAVAILABLE`;
- `SOURCE_INDEX_SCHEMA_UNSUPPORTED`;
- `DIRTY_STATE_UNAVAILABLE`;
- `PACKAGE_METADATA_UNAVAILABLE`;
- `WORKSPACE_PATH_EXCLUDED`; and
- `PROJECT_MODEL_OWNERSHIP_UNKNOWN`.

Malformed backend JSON is a fatal `WORKSPACE_FILES_BACKEND_INVALID` error.
Exact-root admission failure remains the ADR 0019 error. Backend capability
absence or source-index absence may degrade if the other candidate source is
usable. If neither candidate source is usable, the command fails with
`WORKSPACE_FILE_DISCOVERY_UNAVAILABLE` rather than returning an empty list.

## Capability verification

`AgentPublicCapabilityRoute` is a typed registry entry with backend capability
and public command path. Verification intersects raw backend capabilities with
this registry and emits:

```json
{
  "capability": "WORKSPACE_FILES",
  "command": "kast agent workspace-files"
}
```

only when both sides exist. A Clap contract test resolves each registry path
against `Cli::command()`. Removing or renaming the command therefore breaks
the same test that authorizes the public capability projection. Issue #342
extends this small registry instead of introducing a second hand-maintained
catalog.

## #340 reuse boundary

Issue #340 calls the internal collector before public filtering and limiting.
It may inspect `KotlinScript` candidates, module/source-root facts, and source
coverage to classify Gradle Kotlin DSL files. It must not treat
`WorkspaceFileDrift::IndexOnly` or `Unknown` as compiler project-model
ownership. Dynamic Gradle constructs retain typed degraded evidence instead
of becoming not-found.

The inventory API does not expose CLI output types to #340. Gradle script
classification is an additive domain layer over `WorkspaceInventorySnapshot`,
so #340 can evolve its own semantic types without destabilizing the public
file projection.

## Testing strategy

The TDD sequence is:

1. Add CLI/help regressions proving `workspace-files` is public and retired
   raw aliases remain unavailable.
2. Add source-index reader tests for package/module/source-set joins, `.kt`
   and `.kts` kinds, exact-root containment, absolute prefix decoding, schema
   failure, and an uncapped high-cardinality snapshot.
3. Add composition tests for every drift truth-table row, especially
   truncated/unavailable backend evidence never producing `INDEX_ONLY`.
4. Add Git porcelain tests for clean, modified, added, deleted, renamed,
   untracked, conflicted, and unavailable states.
5. Add command tests for every filter, deterministic ordering, default and
   maximum bounds, typed limitations, all ADR 0020 views, and a default output
   below 120 lines/1,500 estimated tokens.
6. Add a semantic ownership regression with an unowned `.kt` file on disk;
   assert it is omitted because no recursive filesystem search supplies
   candidates.
7. Pipe a returned `filePath` into diagnostics and use it as a symbol
   `--file-hint`, proving direct composition.
8. Add capability tests proving `WORKSPACE_FILES` is projected only through a
   registry entry whose Clap path exists.
9. Run full Rust, contract, docs, and rendering gates. Kotlin modules are not
   changed; the existing backend workspace-files tests remain the wire proof.

## Non-goals

This issue does not paginate or redesign `raw/workspace-files`, change the
source-index schema, recursively search the filesystem, use Git as candidate
authority, classify Gradle task declarations, infer dynamic Gradle semantics,
or expose arbitrary RPC dispatch. It does not solve the full capability and
installed-guidance lockstep requested by #342; it establishes the
`WORKSPACE_FILES` registry invariant that #342 will generalize.
