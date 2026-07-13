# Workspace File Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `kast agent workspace-files` as a bounded, typed, exact-root
discovery command backed by exhaustively paged compiler/project-model evidence
and the `.kt`-only source index, with `.kts` candidates reusable by issue #340's
separate Gradle DSL index.

**Architecture:** Kotlin adds opaque generation-bound per-module workspace-file
paging and an IDEA project-model inventory for `.kt` and `.kts`. Rust exhausts
one coherent workspace generation, unions physical paths while retaining all
module owners, joins `.kt` index evidence, maps Git porcelain from repository
root to the admitted root, then applies public filters, projections, and
bounds.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, IntelliJ Platform and Gradle
project model, Rust 2024, Clap, serde/serde_json, rusqlite, glob, Git porcelain
v2, tempfile integration fixtures, Markdown, and Zensical.

## Global Constraints

- Rebase onto merged issue #337 before production work and reuse its result
  views instead of recreating them.
- Preserve the `.kt`-only `SourceIndexFilePolicy`; #340 owns the separate
  Gradle DSL index and consumes `.kts` candidates from backend inventory.
- Keep `raw/workspace-files` internal; the public path is exactly
  `kast agent workspace-files`.
- Admit and report the exact normalized workspace root under ADR 0019.
- Never use recursive filesystem discovery or Git as candidate authority.
  Targeted root build/settings lookups are allowed only for roots proved by the
  linked Gradle project model.
- Exhaust every valid backend module page from one workspace generation before
  claiming that module complete. Incomplete possible owners can never prove
  `INDEX_ONLY`.
- Treat snapshot and page tokens as opaque server-owned values. Bind cursors to
  generation, exact module, and offset; never construct or advance them in
  Rust.
- Reuse ADR 0020's discriminated `EXACT | KNOWN_MINIMUM` cardinality. Keep
  candidate-inventory and selected-filter evidence coverage separate; neither
  a bare count nor a completeness boolean may imply exact filtered matches.
- On `STALE_WORKSPACE_INVENTORY`, discard the whole backend attempt and restart
  once. A second stale response is typed partial evidence with no backend
  candidates from either stale attempt.
- Model physical-file backend and indexed module ownership as sorted sets.
- Keep the internal inventory uncapped by public filters and `--limit`.
- Default `--limit` to 20, reject values outside 1 through 200, and keep compact
  output below 120 lines and 1,500 estimated tokens.
- Change Kotlin wire/backend/generated contracts when required by paging;
  regenerate them from their source owners.
- Treat `commands.json` as the hand-authored internal catalog source; regenerate
  only YAML, schemas, and samples from it.
- Preserve Kotlin top-level type isolation for every materially edited
  workspace-file contract; direct sealed response variants may remain with
  their root.
- Add or update scoped `AGENTS.md` whenever source ownership or validation gates
  change. Do not publish ADR/spec/plan files in Zensical navigation.
- Execute tasks sequentially when they edit shared agent/projection files. The
  primary agent reviews every delegated result and runs final verification.
- Preserve unrelated worktree changes and commit each red-green slice
  independently with a conventional commit.

---

### Task 1: Add typed deterministic raw workspace-file paging

**Files:**

- Modify: `analysis-api/AGENTS.md`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/WorkspaceFilesQuery.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/WorkspaceFilesResult.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/WorkspaceModule.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/WorkspaceFileSnapshotToken.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/WorkspaceFilePageToken.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedWorkspaceFilesQuery.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/WorkspaceInventoryStaleException.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/InvalidWorkspaceFileCursorException.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/WorkspaceProjectModelIncompleteException.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/WorkspaceProjectModelIncompleteReason.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastWorkspaceFilesRequest.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastWorkspaceFilesQuery.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastWorkspaceFilesResponse.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt`
- Modify: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ParsedModelsTest.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`

**Interfaces:**

- Produces: `WorkspaceFileSnapshotToken`, `WorkspaceFilePageToken`,
  `ParsedWorkspaceFilesQuery.snapshotToken`,
  `ParsedWorkspaceFilesQuery.pageToken`, `WorkspaceFilesResult.snapshotToken`,
  `WorkspaceModule.returnedFileCount`, `WorkspaceModule.nextPageToken`, and
  explicit `WorkspaceModule.contentRoots`.
- Produces: `WorkspaceProjectModelIncompleteException` with stable
  `WORKSPACE_PROJECT_MODEL_INCOMPLETE` code and typed
  `WorkspaceProjectModelIncompleteReason` details.
- Invariant: snapshot and page tokens are opaque to clients. An input snapshot
  token is legal only with `includeFiles=true` and one exact module; a page
  token additionally requires that snapshot token.

- [ ] **Step 1: Write failing query and server tests**

Add parsing cases for nonblank opaque snapshot/page tokens and rejection of
blank tokens, page token without snapshot token, either token without a module,
either token with `includeFiles=false`, and a page size above server
`maxResults`. Add fake-backend tests for two pages over five sorted files:

```kotlin
val metadata = backend.workspaceFiles(WorkspaceFilesQuery())
val first = backend.workspaceFiles(
    WorkspaceFilesQuery(
        moduleName = "main",
        includeFiles = true,
        maxFilesPerModule = 2,
        snapshotToken = metadata.snapshotToken,
    ),
)
val second = backend.workspaceFiles(
    WorkspaceFilesQuery(
        moduleName = "main",
        includeFiles = true,
        maxFilesPerModule = 2,
        snapshotToken = metadata.snapshotToken,
        pageToken = first.modules.single().nextPageToken,
    ),
)
assertEquals(5, first.modules.single().fileCount)
assertEquals(metadata.snapshotToken, first.snapshotToken)
assertNotNull(first.modules.single().nextPageToken)
assertNotNull(second.modules.single().nextPageToken)
assertTrue(first.modules.single().files.intersect(second.modules.single().files.toSet()).isEmpty())
```

Pass the first page cursor back with a different exact module and assert
`INVALID_WORKSPACE_FILE_CURSOR`. Mutate one path while preserving cardinality,
then add and remove a module after metadata; each subsequent page request must
return `STALE_WORKSPACE_INVENTORY` rather than a mixed page.

Make the fake backend throw each project-model-incomplete reason and assert the
dispatcher preserves status 503, `retryable=true`, stable error code, and exact
`details.reason`. These are typed error-envelope tests, not string matching on
an internal exception message.

- [ ] **Step 2: Run the focused red tests**

```console
./gradlew :analysis-api:test --tests '*ParsedModelsTest*workspace*' :analysis-server:test --tests '*AnalysisDispatcherTest*workspace*' --no-daemon
```

Expected: compilation fails because generation-bound paging fields/types do
not exist.

- [ ] **Step 3: Extract and implement the typed query boundary**

Move `ParsedWorkspaceFilesQuery` out of `ParsedModels.kt` to satisfy top-level
type isolation. Add one nonblank opaque wire-token type per matching file:

```kotlin
@JvmInline
value class WorkspaceFileSnapshotToken private constructor(val value: String) {
    companion object {
        fun fromWire(raw: String): WorkspaceFileSnapshotToken =
            WorkspaceFileSnapshotToken(raw.also { require(it.isNotBlank()) })
    }
}
```

`WorkspaceFilePageToken` has the same nonblank wire boundary but remains a
distinct type. Add `snapshotToken: String?` and `pageToken: String?` to
`WorkspaceFilesQuery`, parse each once, and reject illegal field combinations.
Do not parse offsets or cursor payloads in `analysis-api`; backend providers own
token generation and decoding. Keep the server's positive page-size and
maximum checks. Add matching `AnalysisException` subtypes: stale inventory is
HTTP/JSON-RPC status 409, code `STALE_WORKSPACE_INVENTORY`, and retryable;
invalid cursor is status 400, code `INVALID_WORKSPACE_FILE_CURSOR`, and not
retryable. Add `WorkspaceProjectModelIncompleteException` with status 503,
code `WORKSPACE_PROJECT_MODEL_INCOMPLETE`, `retryable=true`, and a typed reason
serialized into `details.reason`. The existing dispatcher maps all three
through its typed error envelope.

- [ ] **Step 4: Isolate and extend the result type**

Move `WorkspaceModule` to its matching file and add:

```kotlin
// WorkspaceFilesResult
val snapshotToken: String

// WorkspaceModule
val contentRoots: List<String> = emptyList()
val returnedFileCount: Int = files.size
val nextPageToken: String? = null
```

Validate in tests that returned count equals `files.size`, `fileCount` is never
smaller, every page echoes one snapshot token, and continuation tokens are
nonblank. Validate source/content roots are sorted and deduplicated. Update
skill response types and the fake backend with the same generation/fingerprint,
opaque-cursor, sort-before-slice, stale, and cross-module-error contract.

Extract the materially edited `KastWorkspaceFilesRequest` and
`KastWorkspaceFilesQuery` from `SkillContracts.kt` into matching files. Move
`KastWorkspaceFilesResponse` and its direct success/failure variants together
to `KastWorkspaceFilesResponse.kt`; direct sealed variants may remain with
their owner. Add snapshot/page fields to the request/query and the echoed
snapshot to the success response. Do not migrate unrelated legacy skill types.

- [ ] **Step 5: Run Kotlin paging tests green**

Run the Step 2 command. Expected: all paging, validation, stale-generation,
cross-module, and non-overlap tests pass.

- [ ] **Step 6: Update source ownership and commit**

Record query/result/generated ownership and paging gates in
`analysis-api/AGENTS.md`.

```console
git add analysis-api analysis-server
git diff --cached --check
git commit -m "feat: page raw workspace file results"
```

### Task 2: Enumerate project-model Kotlin sources and scripts

**Files:**

- Create: `backend-idea/AGENTS.md`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceFileInventory.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceFileInventorySnapshot.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceFileModuleSnapshot.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceFileCursor.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceInventoryGeneration.kt`
- Create: `backend-idea/src/main/java/io/github/amichne/kast/idea/IdeaGradleWorkspaceFileBridge.java`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt`
- Create: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceFileInventoryTest.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt`
- Verify unchanged: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/api/index/SourceIndexFilePolicy.kt`
- Verify unchanged: `index-store/src/test/kotlin/io/github/amichne/kast/indexstore/SourceIndexFilePolicyTest.kt`

**Interfaces:**

- Produces:
  `IdeaWorkspaceFileInventory.snapshot(): IdeaWorkspaceFileInventorySnapshot`.
- The inventory snapshot has one `IdeaWorkspaceInventoryGeneration` over every
  module. Each module snapshot has sorted contained source/content roots,
  sorted dependency names, and a complete sorted set of project-model `.kt`
  and `.kts` paths.
- `IdeaWorkspaceFileCursor` is a server-owned opaque encoding of generation,
  exact backend module name, and positive next offset.

- [ ] **Step 1: Write failing project-model inventory tests**

Create one IDEA fixture containing:

- root `settings.gradle.kts` and `build.gradle.kts`;
- `build-logic/src/main/kotlin/convention.gradle.kts`;
- `scripts/release.main.kts`;
- an included build with its own settings/build scripts;
- one `.kt` shared by two module content roots; and
- an outside-root `.kts` exposed by a test project scope.

Assert the first five categories are candidates, the shared file appears in
both module snapshots, and the outside path is absent. Capture a generation,
then replace one path with another while preserving total cardinality, add a
module, and remove a module. Each change must alter the generation. Add backend
contract cases proving the old snapshot token returns
`STALE_WORKSPACE_INVENTORY` and a cursor issued for one module returns
`INVALID_WORKSPACE_FILE_CURSOR` when used with another.

Add three failure fixtures: IDEA dumb/index-not-ready state, unavailable linked
Gradle project-model data, and a linked root with no root-module association.
Assert the backend returns `WORKSPACE_PROJECT_MODEL_INCOMPLETE` with respective
typed reasons `RUNTIME_INDEXING`, `PROJECT_MODEL_UNAVAILABLE`, and
`LINKED_ROOT_UNASSOCIATED`. Cover failure on metadata and after a prior page so
Rust can distinguish whole-inventory failure from a generic page transport
failure.

- [ ] **Step 2: Run the focused red backend test**

```console
./gradlew :backend-idea:test --tests '*IdeaWorkspaceFileInventoryTest*' --no-daemon
```

Expected: compilation fails because the inventory does not exist.

- [ ] **Step 3: Implement model-backed candidate collection**

Use `FileTypeIndex` with project and module content scopes for compiler-visible
Kotlin files. Use `ModuleRootManager` content/source roots to retain every
owner. The Java bridge reads `GradleSettings.getLinkedProjectsSettings()` for
direct roots; reads
`GradleProjectSettings.getCompositeBuild()`,
`GradleProjectSettings.CompositeBuild.getCompositeParticipants()`, and
`BuildParticipant.getRootPath()` for included-build roots; and associates
modules with those roots through
`GradleModuleDataIndex.findGradleModuleData(Module)`,
`GradleModuleData.isIncludedBuild()`, `getGradleProjectDir()`, and
`ModuleData.getLinkedExternalProjectPath()`. Return normalized external roots
and root-module associations. For each model-proven root, look up only
`settings.gradle.kts` and `build.gradle.kts`; do not walk directories.

Project-scope `.kts` files under a contained module root acquire all containing
module owners. A contained linked-root script without a content-root owner
acquires every root module associated with that linked root. If the bridge
cannot associate the linked root with any backend root module, throw
`WorkspaceProjectModelIncompleteException(LINKED_ROOT_UNASSOCIATED)`. Map IDEA
dumb/index-not-ready state to `RUNTIME_INDEXING` and missing linked settings or
module data to `PROJECT_MODEL_UNAVAILABLE`; do not allow either to become an
empty complete inventory. Canonical containment is mandatory before ownership
is recorded.

- [ ] **Step 4: Page sorted inventory in the backend**

Replace cap-before-sort logic with generation validation followed by slicing:

```kotlin
val current = inventory.snapshot()
val requestedGeneration = decodeSnapshotToken(query.snapshotToken)
if (requestedGeneration != current.generation) {
    throw WorkspaceInventoryStale
}
val cursor = query.pageToken?.let(::decodePageCursor)
cursor?.requireGenerationAndModule(current.generation, query.moduleName)
val allFiles = current.module(query.moduleName).filePaths
val offset = cursor?.nextOffset ?: 0
val files = if (query.includeFiles) allFiles.drop(offset).take(fileLimit) else emptyList()
val nextOffset = offset + files.size
val nextToken = nextOffset
    .takeIf { query.includeFiles && it < allFiles.size }
    ?.let { encodePageCursor(current.generation, query.moduleName, it) }
```

Build and fingerprint the canonical full inventory in one IDEA read action.
The fingerprint includes sorted module identities, source/content roots,
dependency names, and file paths, so equal-cardinality replacement and module
add/remove cannot reuse a generation. Metadata requests return every sorted
module plus the opaque snapshot token. Exact-module requests must match that
generation before slicing; malformed, out-of-range, or cross-module cursors
return `INVALID_WORKSPACE_FILE_CURSOR`. Populate stable counts and opaque
tokens on every page.

- [ ] **Step 5: Prove scripts, multiple owners, and pages**

Run:

```console
./gradlew :backend-idea:test --tests '*IdeaWorkspaceFileInventoryTest*' --tests '*KastPluginBackendContractTest*workspace files*' --no-daemon
./gradlew :index-store:test --tests '*SourceIndexFilePolicyTest*' --no-daemon
```

Expected: project scripts, shared ownership, included-build associations,
generation changes, stale responses, and cross-module rejection pass; the
typed indexing/project-model failures retain their reason; the index-store test
still proves `.kts` rejection.

- [ ] **Step 6: Commit backend authority**

```console
git add backend-idea
git diff --cached --check
git commit -m "feat: enumerate project model Kotlin scripts"
```

`backend-idea/AGENTS.md` records that the inventory and Java Gradle bridge own
model-proven `.kt`/`.kts` candidates, generation-bound paging, typed
project-model incompleteness, and the focused backend tests above. This is the
nearest guide for the new source boundary.

Do not stage either source-index policy file; both are verification-only.

### Task 3: Establish the typed public CLI boundary

**Files:**

- Modify: `cli-rs/src/cli/agent.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/agent/projection/view.rs`
- Create: `cli-rs/src/agent/workspace_files.rs`
- Create: `cli-rs/tests/agent_workspace_files_smoke.rs`
- Modify: `cli-rs/tests/cli_core_smoke.rs`

**Interfaces:**

- Produces: `AgentCommand::WorkspaceFiles`, typed filters,
  `AgentWorkspaceFilesField`, and a temporary typed unavailable result.
- Consumes: ADR 0020 `AgentResultView` and existing `AgentRuntimeArgs`.

- [ ] **Step 1: Write failing command/help/argument tests**

Move `workspace-files` from retired aliases to visible agent commands. Assert
all documented flags parse and reject limit `0`/`201`, absolute or parent path
prefixes, `regex:` globs, blank selectors, and incompatible result views.
Include `--drift not-applicable`.

- [ ] **Step 2: Run the red command tests**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test cli_core_smoke smoke_core_cli_commands
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke workspace_files_is_public
```

Expected: Clap does not recognize `workspace-files`.

- [ ] **Step 3: Add typed args and filters**

Add `AgentWorkspaceFilesArgs` with `AgentRuntimeArgs`, the family-specific
ADR 0020 view args, optional module/source-set/kind/package/dirty/drift/path
prefix/glob filters, and `WorkspaceFileLimit`. Use private-field newtypes and
`FromStr` validation. The drift enum is:

```rust
pub enum WorkspaceDriftFilter {
    None,
    FilesystemOnly,
    IndexOnly,
    MissingOnDisk,
    NotApplicable,
    Unknown,
}
```

- [ ] **Step 4: Wire a typed temporary failure**

Add exhaustive dispatch and projection request branches, but return
`WORKSPACE_FILE_DISCOVERY_UNAVAILABLE` until inventory exists. Do not claim
discovery works.

- [ ] **Step 5: Run command parsing green and commit**

Run Step 2, then:

```console
git add cli-rs/src/cli/agent.rs cli-rs/src/agent.rs cli-rs/src/agent/dispatch.rs cli-rs/src/agent/projection/view.rs cli-rs/src/agent/workspace_files.rs cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/tests/cli_core_smoke.rs
git diff --cached --check
git commit -m "feat: add typed workspace file command boundary"
```

### Task 4: Build the uncapped `.kt` source-index inventory

**Files:**

- Modify: `cli-rs/src/main.rs`
- Create: `cli-rs/src/workspace_inventory.rs`
- Create: `cli-rs/src/workspace_inventory/AGENTS.md`
- Create: `cli-rs/src/workspace_inventory/model.rs`
- Create: `cli-rs/src/workspace_inventory/index.rs`
- Create: `cli-rs/src/workspace_inventory/tests.rs`
- Modify: `cli-rs/tests/support/mod.rs`
- Create: `cli-rs/tests/support/workspace_files.rs`

**Interfaces:**

- Produces: `WorkspaceRoot`, `WorkspaceFilePath`,
  `WorkspaceIndexSnapshot`, `WorkspacePackageEvidence`,
  `WorkspaceIndexRead`, `WorkspaceInventoryLimitationCode`,
  `WorkspaceMatchCoverage`, and `read_workspace_index(&WorkspaceRoot)`.
- The index reader has no public limit and returns `.kt` rows only.

- [ ] **Step 1: Write failing schema, path, and package-state tests**

Seed 500 `.kt` rows plus non-Kotlin, `.kts`, relative-escape, absolute,
outside-root, and symlink-escape rows. Add four package cases:

1. no `file_metadata` row;
2. metadata with null `package_fq_id`;
3. metadata with a joined package name; and
4. metadata with dangling `package_fq_id`.

Assert 500 valid `.kt` candidates, zero `.kts`, exact package variants, and
typed excluded/invalid counts.

- [ ] **Step 2: Run the red inventory tests**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
```

Expected: compilation fails because inventory types do not exist.

- [ ] **Step 3: Define the invariant-carrying model**

Use sorted sets for owners and source sets:

```rust
pub(crate) struct WorkspaceInventoryFile {
    path: WorkspaceFilePath,
    backend_modules: BTreeSet<BackendModuleName>,
    indexed_gradle_modules: BTreeSet<GradleModulePath>,
    source_sets: BTreeSet<WorkspaceSourceSet>,
    kind: WorkspaceFileKind,
    package: WorkspacePackageEvidence,
    index_state: WorkspaceFileIndexState,
    drift: WorkspaceFileDrift,
    dirty_state: WorkspaceFileDirtyState,
    evidence: BTreeSet<WorkspaceEvidenceSource>,
}
```

Include `NotApplicable` in source-index state and drift. Keep fields private
with read-only accessors required by agent/#340 consumers.

Define closed limitation variants for backend capability, metadata, page,
stale generation, runtime indexing, unavailable project model, unassociated
linked root, source-index unavailable/incompatible, Git unavailable, package
metadata invalid, unknown project-model ownership, and out-of-root exclusion.
Keep `WorkspaceMatchCoverage` as two typed dimensions:
`candidate_inventory` and `filter_evidence`, each `Complete` or `Partial`.
This prevents a complete candidate set from asserting exact filtered matches
when a requested predicate is unknown.

- [ ] **Step 4: Implement the read-only query exactly from the design**

Select `metadata_present`, `package_fq_id`, and joined `fq_name` separately.
Use `SQLITE_OPEN_READ_ONLY | SQLITE_OPEN_URI`, configure query-only access,
verify schema/tables, decode existing path prefixes, reject non-`.kt`, and map
the four package states without collapsing nulls.

- [ ] **Step 5: Add the scoped ownership guide and verify**

State that this unit owns uncapped exact-root composition, `.kt` index reads,
set-valued owners, backend page coverage, and Git annotation. Prohibit `.kts`
source-index reads and filesystem/Git candidate enumeration.

Run Step 2. Expected: all 500 rows and package/path invariants pass.

- [ ] **Step 6: Commit the index boundary**

```console
git add cli-rs/src/main.rs cli-rs/src/workspace_inventory.rs cli-rs/src/workspace_inventory cli-rs/tests/support/mod.rs cli-rs/tests/support/workspace_files.rs
git diff --cached --check
git commit -m "feat: add uncapped Kotlin source inventory"
```

### Task 5: Exhaust backend pages and compose ownership, drift, and Git evidence

**Files:**

- Create: `cli-rs/src/workspace_inventory/backend.rs`
- Create: `cli-rs/src/workspace_inventory/dirty.rs`
- Create: `cli-rs/src/workspace_inventory/collect.rs`
- Modify: `cli-rs/src/workspace_inventory/model.rs`
- Modify: `cli-rs/src/workspace_inventory/tests.rs`
- Modify: `cli-rs/src/workspace_inventory.rs`
- Modify: `cli-rs/tests/support/workspace_files.rs`
- Modify: `cli-rs/tests/agent_workspace_files_smoke.rs`
- Modify: `cli-rs/tests/semantic_workspace_admission_smoke.rs`

**Interfaces:**

- Produces:
  `collect_workspace_inventory(WorkspaceInventoryInputs) -> Result<WorkspaceInventorySnapshot>`.
- Backend collection uses one admitted `RawRpcSession`, a top-level opaque
  snapshot token, exact-module opaque cursors, bounded restart, and typed
  `BackendWorkspaceCoverage`/`BackendModuleCoverage`.

- [ ] **Step 1: Write failing page-exhaustion and multi-owner tests**

Script three pages for one module and two pages for a second. Repeat one
physical path in both modules. Assert every page is requested in order, the
physical record has both owners, pages do not overlap, and all modules are
complete. Add failures for repeated/non-advancing tokens, changed totals,
overlap, missing module, and a page failure after earlier successes; only the
affected module becomes partial.

Add three generation regressions:

- replace one path with another while preserving `fileCount` between pages;
- add or remove a module after metadata; and
- return a cursor bound to a different module.

Assert the first two produce `STALE_WORKSPACE_INVENTORY`, discard every backend
page from that attempt, and restart from fresh metadata exactly once. Assert a
second stale response does not restart again: backend coverage is typed
partial, every module from the last metadata response is partial,
`BACKEND_WORKSPACE_INVENTORY_STALE` is emitted, and no stale backend candidate
is composed. Cross-module cursor use is invalid and never becomes a page.

Script typed `WORKSPACE_PROJECT_MODEL_INCOMPLETE` failures for metadata and for
a later page after earlier modules succeeded. Assert metadata failure produces
backend-unavailable coverage, page failure discards the whole backend attempt,
neither consumes the stale retry, and neither composes a backend candidate.
Map `RUNTIME_INDEXING`, `PROJECT_MODEL_UNAVAILABLE`, and
`LINKED_ROOT_UNASSOCIATED` to distinct Rust limitations. Keep a generic page
transport failure local to only its requested module.

- [ ] **Step 2: Write failing drift tests**

Prove:

- `.kts` backend candidates are `NOT_APPLICABLE` to source index/drift;
- backend-only `.kt` is `FILESYSTEM_ONLY`;
- complete-owner index-only `.kt` is `INDEX_ONLY`;
- any partial overlapping owner makes it `UNKNOWN`; and
- missing files are `MISSING_ON_DISK` with independent index state.

Also prove workspace-wide stale or project-model partial coverage can never
produce `INDEX_ONLY`, including when the last metadata response contains zero
modules. Unknown current module membership is not a vacuously complete owner
set.

- [ ] **Step 3: Write nested Git mapping tests**

Create a repository with an admitted Gradle workspace below the Git top level
and set `status.relativePaths=true` in its config. Cover modified, added,
deleted, untracked, conflicted, inside-to-inside rename, outside-to-inside
rename, and inside-to-outside rename. Assert the adapter's explicit
`status.relativePaths=false` override makes porcelain paths repository-root
relative, the exact workspace prefix is stripped, and only contained endpoints
annotate candidates. A successful mapped snapshot alone may assign `CLEAN`.

- [ ] **Step 4: Add the root-A/root-B exact-root regression**

In both smoke files, configure root A with only root B's ready descriptor and
source-index database. Invoke:

```console
kast agent workspace-files --workspace-root <root-a> --backend idea
```

Assert the typed exact-root rejection, zero `raw/workspace-files` requests,
and no read/open observation for root B's database. This is a rejected-no-request
test, not merely an output-path assertion.

- [ ] **Step 5: Run the focused red tests**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test semantic_workspace_admission_smoke workspace_files
```

Expected: generation-bound paging, bounded restart, composition, Git, and
exact-root cases fail because the collector is absent.

- [ ] **Step 6: Implement strict backend paging**

Fetch module metadata and its opaque snapshot token, sort exact module names,
and echo the snapshot while requesting opaque cursors until each module
returns no next token. Never parse or construct a token in Rust. Validate
total/returned counts, cursor advancement, echoed snapshot, module identity,
non-overlap, and containment before merging. Preserve duplicate physical paths
as one record with a `BTreeSet` of owners.

On the first `STALE_WORKSPACE_INVENTORY`, discard the entire backend attempt
and restart once from fresh metadata. If the retry is stale, discard it too,
return `BackendWorkspaceCoverage::Partial` with
`BACKEND_WORKSPACE_INVENTORY_STALE`, and mark every last-known module partial.
No candidates from either stale attempt survive. Other page failures record
only that module as `BackendModuleCoverage::Partial`; they never silently
truncate.

Decode the stable API error code and typed reason before classifying a failed
metadata/page response. Metadata project-model failure is
`BackendWorkspaceCoverage::Unavailable`. Project-model failure on any page is
workspace-wide `Partial` and discards earlier pages from that attempt. Preserve
the exact runtime-indexing, project-model-unavailable, or unassociated-root
limitation. Only generic transport/invalid-response failure remains local to
one module, and only `STALE_WORKSPACE_INVENTORY` consumes the single restart.

- [ ] **Step 7: Implement conservative composition**

Candidate keys come only from backend pages and `.kt` index rows. For index-only
`.kt`, associate all canonical containing module roots; `INDEX_ONLY` requires
every possible owner complete. Unknown or overlapping partial ownership emits
`PROJECT_MODEL_OWNERSHIP_UNKNOWN`. `.kts` never queries the source index and
uses `NotApplicable` states.

- [ ] **Step 8: Implement exact-root Git mapping**

Resolve Git top level, prove containment, run porcelain v2 with
`-c status.relativePaths=false` and `-- .`, and map both current and original
paths from repository root through the exact workspace prefix. Parse records
`1`, `2`, `u`, and `?`. Invalid bytes or mapping failure produces
`DirtyWorkspaceCoverage::Unavailable`.

- [ ] **Step 9: Run focused tests green and commit**

Run Step 5, then:

```console
git add cli-rs/src/workspace_inventory.rs cli-rs/src/workspace_inventory cli-rs/tests/support/workspace_files.rs cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/tests/semantic_workspace_admission_smoke.rs
git diff --cached --check
git commit -m "feat: compose exhaustive workspace file evidence"
```

### Task 6: Project bounded public discovery and callable capability evidence

**Files:**

- Modify: `cli-rs/src/agent/workspace_files.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/projection.rs`
- Modify: `cli-rs/src/agent/projection/view.rs`
- Create: `cli-rs/src/agent/projection/workspace_files.rs`
- Create: `cli-rs/src/agent/public_capabilities.rs`
- Modify: `cli-rs/src/agent/projection/verify.rs`
- Modify: `cli-rs/src/agent/projection/tests.rs`
- Modify: `cli-rs/tests/agent_workspace_files_smoke.rs`
- Modify: `cli-rs/tests/agent_result_projection_smoke.rs`

**Interfaces:**

- Produces: `KAST_AGENT_WORKSPACE_FILES_RESULT` views and
  `AgentPublicCapabilityRoute` for `WORKSPACE_FILES`.
- Reuses: ADR 0020 `AgentResultCardinality::Exact | KnownMinimum` with
  `returnedCount`, `truncated`, and separate typed candidate/filter coverage.

- [ ] **Step 1: Write failing output, filter, limitation, and budget tests**

Assert compact records contain sorted owner sets, source sets, kind, structured
package evidence, source-index state, drift, dirty state, and paths. Cover each
filter and conjunction; partial pages, unavailable index/Git, invalid package
reference, and both candidate sources unavailable. Seed 500 records and assert
20 default records, at most 120 lines, and at most 1,500 estimated tokens.

Assert `EXACT.totalCount` only when candidate inventory and every selected
predicate are complete. Cover these counterexamples explicitly:

- complete candidate inventory plus unavailable Git and `--dirty clean` is
  `KNOWN_MINIMUM` with partial filter evidence;
- unavailable package/source-set metadata with a corresponding filter is
  `KNOWN_MINIMUM` even if backend paging is complete;
- partial backend pages or unavailable source-index candidate authority are
  `KNOWN_MINIMUM`; and
- a complete inventory without a predicate that depends on unavailable Git is
  still `EXACT`.

In every view, assert `returnedCount == files.len()`. `truncated` is true when
an exact total exceeds returned count or cardinality is `KNOWN_MINIMUM`.
Count-only group values also use `Exact` or `KnownMinimum`; no bare count may
imply exactness.

- [ ] **Step 2: Write failing capability route tests**

Define the expected initial route:

```rust
AgentPublicCapabilityRoute {
    capability: AgentPublicCapability::WorkspaceFiles,
    command_segments: &["agent", "workspace-files"],
    display_command: "kast agent workspace-files",
}
```

Assert the registry path resolves through `Cli::command()`. Verification emits
public-read evidence only when backend `WORKSPACE_FILES` and the route both
exist.

- [ ] **Step 3: Run focused red tests**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke workspace_files
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke verify
```

- [ ] **Step 4: Execute admitted collection and apply typed filters**

Call `semantic_workspace_route`, copy the admitted root/backend into runtime
args, open one raw session, collect the uncapped snapshot, then filter. Match
module against any owner, sort by relative path and sorted owner sets, and only
then take `limit`. Compute candidate-inventory and selected-filter evidence
coverage before projection. Do not infer exact match cardinality merely from a
fully consumed known-candidate vector.

- [ ] **Step 5: Add compact, fields, count, verbose, and explain projections**

Compact and selected views never contain raw envelopes. Count groups known
records by kind/index/drift/dirty, with the same discriminated exactness as the
overall result. Compact and selected pages serialize `cardinality`,
`returnedCount`, `truncated`, and `coverage.candidateInventory` plus
`coverage.filterEvidence`. Verbose adds per-module page coverage; explain adds
normalized query and classification evidence. Preserve typed backend/index
failures in failed envelopes.

- [ ] **Step 6: Implement the route registry and verification intersection**

Use one typed enum-to-backend-capability match. Keep raw capability counts for
diagnosis, but expose public command evidence only through the registry
intersection. #342 extends this owner rather than creating another list.

- [ ] **Step 7: Run focused tests green and commit**

Run Step 3, then:

```console
git add cli-rs/src/agent.rs cli-rs/src/agent cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/tests/agent_result_projection_smoke.rs
git diff --cached --check
git commit -m "feat: expose bounded workspace file discovery"
```

### Task 7: Regenerate contracts, prove composition, and teach the public path

**Files:**

- Regenerate: `cli-rs/protocol/`
- Modify: `cli-rs/resources/kast-skill/references/commands.json`
- Regenerate: `cli-rs/resources/kast-skill/references/commands.yaml`
- Regenerate: `cli-rs/resources/kast-skill/references/requests/raw/workspace-files/`
- Modify: `cli-rs/tests/rpc_catalog_smoke.rs`
- Modify: `cli-rs/tests/agent_workspace_files_smoke.rs`
- Modify: `cli-rs/src/agent/AGENTS.md`
- Modify: `cli-rs/resources/kast-skill/SKILL.md`
- Modify: `cli-rs/resources/kast-skill/references/quickstart.md`
- Modify: `docs/reference/agent-commands.md`
- Modify: `docs/use/inspect-kotlin.md`

**Interfaces:**

- Produces generated paging contracts, public/package guidance, and direct
  diagnostics/symbol composition proof.

- [ ] **Step 1: Regenerate Kotlin-owned protocol artifacts**

Generate protocol/OpenAPI artifacts from the Kotlin contract first:

```console
./gradlew :analysis-server:generateDocPages --no-daemon
```

Then hand-edit `commands.json`, the source catalog, to add opaque
`snapshotToken`/`pageToken` request fields and replace the stale capped-secondary
description with the current internal generation-bound paging contract. The
release generator consumes that JSON; it does not produce it. Refresh the
catalog-derived block in `cli-rs/protocol/api-specification.md`, regenerate the
derived YAML, schemas, and samples, then prove requests include both tokens,
results include the top-level snapshot token, and module results include
returned count/next token:

```console
python3 .github/scripts/render-rpc-contract-summary.py --write
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract
cargo test --manifest-path cli-rs/Cargo.toml --locked --test rpc_catalog_smoke
```

- [ ] **Step 2: Add direct composition regression**

Run workspace discovery, extract one `filePath`, then invoke diagnostics and
symbol `--file-hint`. Assert both backend requests receive exactly that path.
Keep an unowned on-disk `.kt` file and assert it is absent.

- [ ] **Step 3: Update ownership and packaged guidance**

Add the command to `cli-rs/src/agent/AGENTS.md`. Teach source/script filters,
partial limitations, one bounded stale-snapshot retry, owner sets, and direct
path composition. State explicitly that `.kts` is not in the Kotlin source
index and Gradle semantic declarations arrive with #340.

- [ ] **Step 4: Update reference and how-to docs**

Document every flag, limit, result view, owner set, package state, drift/index
truth table, limitations, exact-root behavior, generation-bound paging, and the
typed partial result after repeated staleness. Replace generic-search-first
guidance with `workspace-files`, then diagnostics or exact symbol lookup.

- [ ] **Step 5: Validate guidance and commit**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke --test agent_workspace_files_smoke --test rpc_catalog_smoke
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
git add cli-rs/protocol cli-rs/resources cli-rs/tests/rpc_catalog_smoke.rs cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/src/agent/AGENTS.md docs
git diff --cached --check
git commit -m "docs: teach semantic workspace file discovery"
```

### Task 8: Run full gates and prepare issue handoff

**Files:**

- Review: all issue #338 changes
- Update only when a gate proves drift in files already owned by this plan

- [ ] **Step 1: Run focused Rust gates**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_result_projection_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test cli_core_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test semantic_workspace_admission_smoke
```

- [ ] **Step 2: Run Kotlin and source-index authority gates**

```console
./gradlew :analysis-api:test :analysis-server:test :index-store:test :backend-idea:test --no-daemon
```

Expected: generation/fingerprint, stale, cursor-binding, paging/project-model
tests pass and `.kts` remains rejected by `SourceIndexFilePolicyTest`.

- [ ] **Step 3: Run full Rust quality gates**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
```

- [ ] **Step 4: Prove generated contracts and docs are current**

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
python3 .github/scripts/render-rpc-contract-summary.py --check
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```

- [ ] **Step 5: Review scope and whitespace**

```console
git status --short --branch
git diff --stat origin/main...HEAD
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD
```

Expected: issue source/tests/guidance/ADR/spec/plan and required generated
contracts only; no source-index schema or `SourceIndexFilePolicy` change.

- [ ] **Step 6: Request independent review**

Ask a fresh reviewer to check generation-bound paging determinism,
equal-cardinality/module-set stale detection, single bounded restart,
cross-module cursor rejection, included-build project-model script authority,
`.kt`-only source-index preservation, multi-owner physical files, nested Git
mapping, exact-root rejected-no-request proof, package-state SQL, false
`INDEX_ONLY`, project-model error/reason mapping, metadata failure, zero-module
global partiality, candidate versus filter coverage, `EXACT` versus
`KNOWN_MINIMUM`, hand-authored catalog ownership, Kotlin type isolation,
scoped ownership guidance, capability callability, #340 reuse, budgets, and
every issue acceptance criterion. Repair each blocking finding with a focused
red-green commit and rerun the affected gate plus full Rust and Kotlin suites.
