# Workspace File Discovery Implementation Plan

> **For agentic workers:** Follow the root `AGENTS.md` Sub-Agent Delegation
> contract. The primary agent may delegate concrete independent tasks, remains
> responsible for integration and review, and runs final verification. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `kast agent workspace-files` as a bounded, typed, exact-root
discovery command backed by exhaustively paged compiler/project-model evidence
and the `.kt`-only source index, with `.kts` candidates reusable by issue #340's
separate Gradle DSL index.

**Architecture:** Kotlin adds shared server-held opaque continuation state,
generation-bound per-module workspace-file paging, and an IDEA project-model
inventory for `.kt` and `.kts`. Rust exhausts backend pages, reads
generation/progress/pending-aware `.kt` index evidence, and accepts the
composition only after backend, index, targeted filesystem, and Git stamps pass
a bounded stability barrier. It then applies public filters, projections,
bounds, and query-bound continuation.

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
- Reuse one typed server-held opaque continuation store for ADR 0020 and
  workspace-file raw/public paging. Give it positive TTL/capacity limits,
  deterministic eviction, single-use page handles, query binding, and typed
  malformed/forged/unknown/expired failures. Rust never decodes handle state.
  Use distinct token/state namespaces and typed instances of the same generic
  mechanism; never mix continuation families in one untyped map.
- Give the generic store exact-once disposal ownership. Expiry, eviction,
  replacement, query mismatch, explicit completion/invalidation, terminal
  consume, and server shutdown must all remove through the same disposer path;
  #337 closeable IDEA traversal state is adapted to that owner.
- Rust page validation is limited to non-repeated handles, non-overlapping
  physical paths, and cumulative returned evidence; generation/module/offset
  integrity belongs to the server-held state.
- Reuse ADR 0020's discriminated `EXACT | KNOWN_MINIMUM` cardinality. Keep
  candidate-inventory and selected-filter evidence coverage separate; neither
  a bare count nor a completeness boolean may imply exact filtered matches.
- On `STALE_WORKSPACE_INVENTORY`, discard the whole backend attempt and restart
  once. A second stale response is typed partial evidence with no backend
  candidates from either stale attempt.
- Model physical-file backend and indexed module ownership as sorted sets.
- Never parse `file_metadata.module_path` as `GradleProjectPath`. Persist and
  read a separate build-qualified project-model tuple from the IDEA producer;
  keep an IDEA module-name fallback only as a legacy unproven label.
- Keep the internal inventory uncapped by public filters and `--limit`.
- Default `--limit` to 20, reject values outside 1 through 200, and keep compact
  output below 120 lines and 1,500 estimated tokens.
- Return a public `nextPageToken` when more known filtered results remain. Bind
  it to the coherent composition stamp and every result-affecting query field;
  never restart mismatched or stale continuation at page one.
- Read source-index generation, module progress, and pending updates with the
  candidate rows. Increment generation transactionally on relevant writes and
  require complete progress plus zero pending updates before exact coverage.
- Derive source-only, script-only, or mixed relevance before collection. The
  raw backend generation and composition barrier cover only the selected kind
  domain; `.kt` index state is irrelevant to script-only discovery and #340.
  Keep per-kind candidate/filter coverage so mixed grouped counts remain honest.
- Prove missing paths by canonicalizing their deepest existing ancestor.
  Exclude and type any containment that cannot be proved.
- Revalidate only kind/query-relevant backend, source-index, targeted
  filesystem, and Git lane states after composition. Retry the entire attempt
  once when a relevant lane moves; a second change
  or stable incomplete relevant lane forbids `EXACT` for its kind partition.
  Enforce a ceiling of two
  composition attempts with at most two backend-generation attempts each.
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
- Final acceptance must run both `./gradlew test` and
  `./gradlew buildIdeaPlugin` after focused module gates.

---

### Task 1: Add typed deterministic raw workspace-file paging

**Files:**

- Modify: `analysis-api/AGENTS.md`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/WorkspaceFilesQuery.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/WorkspaceFilesResult.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/WorkspaceFileKindDomain.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/ServerLimits.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/WorkspaceModule.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/WorkspaceFileSnapshotToken.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/WorkspaceFilePageToken.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStore.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationTtl.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationCapacity.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationClock.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/continuation/ContinuationStateDisposer.kt`
- Create: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/continuation/ServerHeldContinuationStoreTest.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedWorkspaceFilesQuery.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/WorkspaceInventoryStaleException.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/InvalidWorkspaceFileCursorException.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/InvalidWorkspaceFileCursorScope.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/WorkspaceProjectModelIncompleteException.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/WorkspaceProjectModelIncompleteReason.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/ParsedModels.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastWorkspaceFilesRequest.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastWorkspaceFilesQuery.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastWorkspaceFilesResponse.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt`
- Modify: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ParsedModelsTest.kt`
- Create: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/ServerLimitsTest.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`

**Interfaces:**

- Produces: `WorkspaceFileSnapshotToken`, `WorkspaceFilePageToken`,
  `WorkspaceFileKindDomain`,
  `ParsedWorkspaceFilesQuery.snapshotToken`,
  `ParsedWorkspaceFilesQuery.pageToken`, `WorkspaceFilesResult.snapshotToken`,
  `WorkspaceModule.returnedFileCount`, `WorkspaceModule.nextPageToken`, and
  explicit `WorkspaceModule.contentRoots`.
- Produces: `WorkspaceProjectModelIncompleteException` with stable
  `WORKSPACE_PROJECT_MODEL_INCOMPLETE` code and typed
  `WorkspaceProjectModelIncompleteReason` details.
- Produces: reusable `ServerHeldContinuationStore<Token, Query, State>` with
  typed positive TTL/capacity, injected clock, deterministic eviction,
  reusable leases, single-use consumption, and exact-once state disposal.
- Invariant: snapshot and page tokens are canonical random UUID handles, not
  encoded state. An input snapshot token is legal with `includeFiles=true` and
  one exact module, or with `includeFiles=false` and no module for final barrier
  validation. A page token additionally requires the paging form. Every page
  and validation request reproduces the metadata request's exact kind domain.

- [ ] **Step 1: Write failing query and server tests**

Add parsing cases for canonical opaque snapshot/page handles and rejection of
blank/noncanonical UUIDs, page token without snapshot token, either token with
an illegal module/include-files combination, and a page size above server
`maxResults`. Cover source-only, script-only, and mixed parsing plus kind-domain
mismatch on a snapshot or page handle. Accept only the explicit snapshot-validation form with
`includeFiles=false`, no module, and a snapshot token. Add fake-backend tests
for two pages over five sorted files:

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

Test the shared store with a fake clock and capacity two. Prove TTL expiry,
oldest-expiry capacity eviction, single-use page consumption, reusable snapshot
lease lookup, exact query mismatch, and malformed, forged, unknown, expired,
evicted, or consumed handles all fail without returning state. Rebase onto #337
and migrate `FakeAnalysisBackend` reference/diagnostic continuation maps to this
store before adding fake workspace-file state; do not maintain three ad hoc map
policies.

Use a close-counting fake state and prove exactly one disposal for expiry,
eviction, same-handle replacement, query mismatch, explicit invalidation or
successful completion, terminal consume, callback failure, and server
shutdown. Trigger a second removal path after each case and assert the count
stays one. A throwing disposer must not prevent shutdown from draining and
disposing later entries. Adapt #337's closeable IDEA traversal to the same
test surface so its resource lifetime is not merely theoretical. Race terminal
consume against shutdown and expiry cleanup against replacement; atomic
ownership transfer/removal must still close each fake state once.

Assert invalid raw errors expose only typed `details.scope` of
`SNAPSHOT_HANDLE` or `PAGE_HANDLE`. Snapshot failure discards the workspace-wide
backend attempt; page failure remains module-local only if the snapshot lease
still validates.

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
type isolation. Add one canonical UUID handle type per matching file:

```kotlin
@JvmInline
value class WorkspaceFileSnapshotToken private constructor(val value: String) {
    companion object {
        fun fromWire(raw: String): WorkspaceFileSnapshotToken =
            WorkspaceFileSnapshotToken(parseCanonicalUuid(raw))
    }
}
```

`WorkspaceFilePageToken` has the same canonical wire boundary but remains a
distinct type. Add `snapshotToken: String?` and `pageToken: String?` to
`WorkspaceFilesQuery`, add the closed kind domain, parse each once, and reject
illegal field combinations.
The shared continuation store owns random issue, TTL cleanup, capacity
eviction, query comparison, reusable lookup, single-use consumption, and
exact-once disposal. Its lease/consume APIs accept callbacks rather than
returning owning state; every terminating path removes through one
idempotence-guarded disposer and `close()` drains the store at server shutdown.
Make the dispatcher/server lifecycle the single owner that closes every typed
store instance exactly once.
Do not encode or decode offsets, generations, modules, or queries in a token. Keep
the server's positive page-size and
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
server-held cursor, sort-before-slice, stale, final validation, TTL/capacity,
and cross-module-error contract.

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

Record query/result/generated ownership, exact-once continuation disposal, and
paging/shutdown gates in `analysis-api/AGENTS.md`.

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
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceFileSnapshotLease.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceFileContinuation.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceInventoryGeneration.kt`
- Create: `backend-idea/src/main/java/io/github/amichne/kast/idea/IdeaGradleWorkspaceFileBridge.java`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaProjectIndexer.kt`
- Create: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaWorkspaceFileInventoryTest.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastPluginBackendContractTest.kt`
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaProjectIndexerModuleNameTest.kt`
- Create: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/api/index/BuildQualifiedGradleProjectIdentity.kt`
- Create: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/api/index/GradleProjectPath.kt`
- Create: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/api/index/WorkspaceRelativeGradleBuildRoot.kt`
- Modify: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/api/index/FileIndexUpdate.kt`
- Modify: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/api/index/SourceFileIndexParser.kt`
- Modify: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt`
- Modify: `index-store/src/test/kotlin/io/github/amichne/kast/indexstore/SqliteSourceIndexStoreTest.kt`
- Modify: `index-store/AGENTS.md`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/analysis/PsiSourceIndexScanner.kt`
- Verify unchanged: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/api/index/SourceIndexFilePolicy.kt`
- Verify unchanged: `index-store/src/test/kotlin/io/github/amichne/kast/indexstore/SourceIndexFilePolicyTest.kt`

**Interfaces:**

- Produces:
  `IdeaWorkspaceFileInventory.snapshot(WorkspaceFileKindDomain): IdeaWorkspaceFileInventorySnapshot`.
- The inventory snapshot has one `IdeaWorkspaceInventoryGeneration` over every
  module and the requested kind domain. Each module snapshot has sorted
  contained source/content roots, sorted dependency names, and a complete
  sorted set of project-model paths for that domain.
- `IdeaWorkspaceFileSnapshotLease` and `IdeaWorkspaceFileContinuation` are
  typed server-held states addressed only by opaque UUID handles. They contain
  generation, exact module/query identity, next offset, and cumulative evidence.
- Produces: host-neutral `BuildQualifiedGradleProjectIdentity` containing a
  workspace-relative linked build root and absolute Gradle project path. The
  source index persists a set of those owners in the dedicated
  `file_gradle_projects` association table, distinct from legacy
  `file_metadata.module_path`.

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
`STALE_WORKSPACE_INVENTORY`, the validation-only request catches mutation after
the last page, and a handle issued for one module returns
`INVALID_WORKSPACE_FILE_CURSOR` when used with another. Exercise fake-clock TTL,
capacity eviction, single-use, forged/unknown, and query mismatch through the
real backend adapter.

Exercise source-only, script-only, and mixed raw snapshots. Mutating only a
`.kt` path must not stale a script-only lease, while mutating `.kts` must;
source-only has the inverse rule and mixed reacts to either kind. Passing a
lease or page handle with a different kind domain is an invalid cursor.

Add three failure fixtures: IDEA dumb/index-not-ready state, unavailable linked
Gradle project-model data, and a linked root with no root-module association.
Assert the backend returns `WORKSPACE_PROJECT_MODEL_INCOMPLETE` with respective
typed reasons `RUNTIME_INDEXING`, `PROJECT_MODEL_UNAVAILABLE`, and
`LINKED_ROOT_UNASSOCIATED`. Cover failure on metadata and after a prior page so
Rust can distinguish whole-inventory failure from a generic page transport
failure.

Add a composite-build producer fixture in which both the admitted root build
and an included build contain `:app`. Assert the IDEA adapter emits two
different `BuildQualifiedGradleProjectIdentity` values, the source-index round
trip preserves both build roots/project paths, and workspace identity never
comes from `indexedModuleNameForFilePath` or an IDEA module-name fallback. Add
missing/ambiguous Gradle-data cases that preserve only the legacy unproven label
and leave the build-qualified owner set empty. Add malformed association rows
whose build root or project path fails typed parsing. Prove multiple owners per
file, schema migration/reset, and generation change when an association is
added, replaced, or removed.

After rebasing onto #337, migrate `KastPluginBackend` reference and diagnostic
continuations to the shared store before adding workspace-file leases. Preserve
their query/source/generation behavior and add TTL/capacity/single-use
regressions. Adapt the closeable IDEA traversal to the store's typed disposer
and assert exact-once close on mismatch, terminal consume, expiry/eviction, and
plugin/server shutdown; one mechanism owns all three continuation families.

- [ ] **Step 2: Run the focused red backend test**

```console
./gradlew :backend-idea:test --tests '*IdeaWorkspaceFileInventoryTest*' --tests '*IdeaProjectIndexerModuleNameTest*' :index-store:test --tests '*SqliteSourceIndexStoreTest*' --no-daemon
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

Reuse that bridge in `IdeaProjectIndexer`: resolve each `.kt` file's IDEA module
through `GradleModuleDataIndex`, associate it with one direct/composite linked
build root, and take `GradleModuleData.getGradlePathOrNull()` as the project
path. Construct the host-neutral identity only after both typed components are
proved. Collect every containing module's distinct model-proven identity. Add
the dedicated non-null `file_gradle_projects(prefix_id, filename, build_root,
project_path)` association table, advance the checked-in source-index schema
version, add `FileIndexUpdate.gradleProjects` as a
`Set<BuildQualifiedGradleProjectIdentity>`, and persist the owner set
transactionally. Adding, replacing, or removing an association increments
`schema_version.generation` in that same write transaction.
`PsiSourceIndexScanner` accepts host-neutral module evidence;
no IntelliJ type crosses into `backend-shared` or `index-store`. Keep
`module_path` for existing symbol/metrics behavior,
but workspace discovery must never select or parse it as Gradle identity.

- [ ] **Step 4: Page sorted inventory with server-held state**

Replace cap-before-sort logic with generation validation followed by slicing:

```kotlin
val lease = snapshotStore.requireLease(query.snapshotToken, query.workspaceIdentity)
val current = inventory.snapshot()
lease.requireGeneration(current.generation)
val continuation = query.pageToken?.let { token ->
    pageStore.consume(token, WorkspacePageQuery.from(query))
}
val state = continuation ?: lease.firstPage(query.requireExactModule())
val allFiles = current.module(state.moduleName).filePaths
val offset = state.nextOffset
val files = if (query.includeFiles) allFiles.drop(offset).take(fileLimit) else emptyList()
val nextOffset = offset + files.size
val nextToken = nextOffset
    .takeIf { query.includeFiles && it < allFiles.size }
    ?.let { pageStore.issue(state.advanceTo(it)) }
```

Build and fingerprint the canonical requested-kind inventory in one IDEA read
action. The fingerprint includes the kind domain, sorted module identities,
source/content roots, dependency names, and relevant file paths, so
equal-cardinality replacement and module add/remove cannot reuse a generation,
while excluded-kind movement is irrelevant. Metadata requests store the typed snapshot
lease and return only its opaque handle. Exact-module and final validation
requests recompute and match the leased generation before slicing or success;
unknown state, out-of-range stored offsets, or query/cross-module mismatch
returns `INVALID_WORKSPACE_FILE_CURSOR`. Populate stable counts and opaque
handles on every page. Rust never sees an offset or generation encoding.

- [ ] **Step 5: Prove scripts, multiple owners, and pages**

Run:

```console
./gradlew :backend-idea:test --tests '*IdeaWorkspaceFileInventoryTest*' --tests '*KastPluginBackendContractTest*workspace files*' --no-daemon
./gradlew :index-store:test --tests '*SourceIndexFilePolicyTest*' --tests '*SqliteSourceIndexStoreTest*' --no-daemon
```

Expected: project scripts, shared ownership, included-build associations,
build-qualified root/included project identities, legacy fallback isolation,
generation changes, stale responses, and cross-module rejection pass; the
typed indexing/project-model failures retain their reason; the index-store test
still proves `.kts` rejection.

- [ ] **Step 6: Commit backend authority**

```console
git add backend-idea backend-shared index-store
git diff --cached --check
git commit -m "feat: enumerate project model Kotlin scripts"
```

`backend-idea/AGENTS.md` records that the inventory and Java Gradle bridge own
model-proven `.kt`/`.kts` candidates, server-held generation-bound paging,
TTL/capacity/integrity behavior, typed
project-model incompleteness, build-qualified index-producer identity, and the
focused backend tests above. `index-store/AGENTS.md` records the dedicated
identity association table and prohibits promoting the legacy module label. These are
the nearest guides for the new source boundaries.

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
  `AgentWorkspaceFilesField`, closed `WorkspaceModuleSelector`, distinct
  `WorkspaceFilesPublicPageToken`, and a temporary typed unavailable result.
- Consumes: ADR 0020 `AgentResultView` and existing `AgentRuntimeArgs`.

- [ ] **Step 1: Write failing command/help/argument tests**

Move `workspace-files` from retired aliases to visible agent commands. Assert
all documented flags parse and reject limit `0`/`201`, absolute or parent path
prefixes, `regex:` globs, blank selectors, and incompatible result views.
Include `--drift not-applicable` and canonical `--page-token`; reject blank or
noncanonical handles and page-token/count combinations that cannot emit files.
Accept `backend:<exact-name>`, `gradle:.#:app`, and
`gradle:included/tools#:app`; reject unprefixed/empty selectors, absolute or
escaping build roots, and non-absolute Gradle project paths.

- [ ] **Step 2: Run the red command tests**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test cli_core_smoke smoke_core_cli_commands
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke workspace_files_is_public
```

Expected: Clap does not recognize `workspace-files`.

- [ ] **Step 3: Add typed args and filters**

Add `AgentWorkspaceFilesArgs` with `AgentRuntimeArgs`, the family-specific
ADR 0020 view args, optional module/source-set/kind/package/dirty/drift/path
prefix/glob filters, `WorkspaceFileLimit`, and `WorkspaceFilesPublicPageToken`.
Parse module as `WorkspaceModuleSelector::Backend(BackendModuleName)` or a
`Gradle` variant carrying private validated build-root and project-path selector
newtypes; Task 4 maps that variant to the inventory identity. Derive the raw/composition
source-only, script-only, or mixed domain from the kind filter, with no filter
meaning mixed. Use private-field newtypes and `FromStr` validation. Keep the public token type
distinct from raw snapshot/module-page tokens. The drift enum is:

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
- Modify: `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt`
- Modify: `index-store/src/test/kotlin/io/github/amichne/kast/indexstore/SqliteSourceIndexStoreTest.kt`
- Modify: `index-store/AGENTS.md`
- Modify: `cli-rs/tests/support/mod.rs`
- Create: `cli-rs/tests/support/workspace_files.rs`

**Interfaces:**

- Produces: `WorkspaceRoot`, `WorkspaceFilePath`,
  `WorkspaceIndexSnapshot`, `SourceIndexSnapshotStamp`,
  `SourceIndexGeneration`, `SourceIndexModuleProgress`,
  `SourceIndexPendingCount`, `WorkspacePackageEvidence`,
  `BuildQualifiedGradleProjectIdentity`,
  `WorkspaceIndexRead`, `WorkspaceInventoryLimitationCode`,
  `WorkspaceMatchCoverage`, and `read_workspace_index(&WorkspaceRoot)`.
- The index reader has no public limit and returns `.kt` rows only.

- [ ] **Step 1: Write failing schema, path, and package-state tests**

Seed 500 `.kt` rows plus non-Kotlin, `.kts`, relative-escape, absolute,
outside-root, and symlink-escape rows. Cover a missing in-root leaf, a missing
leaf below an in-root symlink to outside, a dangling symlink, permission or
canonicalization failure, and an ancestor race. The first missing leaf is
admitted through its deepest existing ancestor; every unprovable case is
excluded with `PATH_CONTAINMENT_UNPROVABLE` and partial candidate coverage. Add
four package cases:

1. no `file_metadata` row;
2. metadata with null `package_fq_id`;
3. metadata with a joined package name; and
4. metadata with dangling `package_fq_id`.

Assert 500 valid `.kt` candidates, zero `.kts`, exact package variants, typed
excluded/invalid counts, and no escaping missing path.

Seed a root-build `:app`, an included-build `:app`, a legacy-only
`module_path=idea.app.main`, and malformed association rows. Assert the first
two decode as distinct build-qualified owners of the same file, the legacy
value is never read as Gradle identity, and malformed build-root/project-path
values are typed incompatible evidence rather than partial owners.

Seed generation, `module_index_progress`, and unapplied `pending_updates`.
Assert the snapshot carries their typed state; only a nonempty initialized
progress set with every row `COMPLETE`, indexed count equal to total, and zero
pending updates is exact.
Add store tests proving candidate, progress, and pending-state write
transactions increment generation atomically without changing the schema.

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
    indexed_gradle_projects: BTreeSet<BuildQualifiedGradleProjectIdentity>,
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
linked root, source-index unavailable/incompatible/progress-incomplete/updates-
pending, Git unavailable, unstable cross-source composition, unprovable path
containment, package metadata invalid, unknown project-model ownership, and
out-of-root exclusion.
Keep `WorkspaceMatchCoverage` as two typed dimensions:
`candidate_inventory` and `filter_evidence`, each `Complete` or `Partial`.
This prevents a complete candidate set from asserting exact filtered matches
when a requested predicate is unknown.

- [ ] **Step 4: Implement the read-only query exactly from the design**

Within one SQLite read transaction, select `metadata_present`, `package_fq_id`,
joined `fq_name`, and all joined `file_gradle_projects` rows separately,
plus `schema_version.generation`, all module progress rows, and the unapplied
pending-update count. Never select or parse `module_path` as Gradle ownership. Use
`SQLITE_OPEN_READ_ONLY | SQLITE_OPEN_URI`, configure query-only access, verify
schema/tables, decode existing path prefixes, reject non-`.kt`, and map the four
package states without collapsing nulls. For missing paths, canonicalize the
deepest existing ancestor; lexical containment alone is insufficient.

In `SqliteSourceIndexStore`, increment the existing generation in the same
write transaction as any candidate-table, build-qualified association,
progress, or pending applied-state mutation. Task 2 already adds and versions
the dedicated association table; do not add another ownership column or admit
`.kts`.

- [ ] **Step 5: Add the scoped ownership guide and verify**

Update `index-store/AGENTS.md` to make transactional generation maintenance and
truthful progress/pending state storage-owned gates, and to forbid interpreting
legacy `module_path` as project-model Gradle identity. State in the new Rust guide
that it owns uncapped exact-root composition, generation/progress/pending-aware
`.kt` index reads, deepest-existing-ancestor containment, set-valued owners,
backend page coverage, and Git annotation. Prohibit `.kts` source-index reads
and filesystem/Git candidate enumeration.

Run Step 2. Expected: all 500 rows and package/path invariants pass.

- [ ] **Step 6: Commit the index boundary**

```console
git add index-store cli-rs/src/main.rs cli-rs/src/workspace_inventory.rs cli-rs/src/workspace_inventory cli-rs/tests/support/mod.rs cli-rs/tests/support/workspace_files.rs
git diff --cached --check
git commit -m "feat: add uncapped Kotlin source inventory"
```

### Task 5: Exhaust backend pages and compose ownership, drift, and Git evidence

**Files:**

- Create: `cli-rs/src/workspace_inventory/backend.rs`
- Create: `cli-rs/src/workspace_inventory/dirty.rs`
- Create: `cli-rs/src/workspace_inventory/collect.rs`
- Create: `cli-rs/src/workspace_inventory/barrier.rs`
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
- Produces: `WorkspaceRequestedKindDomain`, `WorkspaceLaneEvidence<Stamp>`,
  `WorkspaceLaneStamp<Stamp>`, `WorkspaceLanePurpose`, and per-kind
  `WorkspaceKindMatchCoverage`. Composition digests canonicalize those exact
  discriminated states.

- [ ] **Step 1: Write failing page-exhaustion and multi-owner tests**

Script three pages for one module and two pages for a second. Repeat one
physical path in both modules. Assert every page is requested in order, the
physical record has both owners, pages do not overlap, and all modules are
complete. Add failures for repeated handles, overlap, cumulative returned
evidence beyond or short of the declared total, and a page failure after
earlier successes; only the affected module becomes partial. Rust must not
decode or infer advancement, generation, module identity, or offset from token
bytes.

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

- [ ] **Step 4: Write the cross-source barrier mutation matrix**

Inject lane observers around collection. Mutate, one case at a time, backend
inventory generation after the last module page, source-index generation,
module progress, pending updates, a candidate's filesystem existence/symlink
resolution, and normalized Git status between before/after reads. Assert the
whole attempt is discarded and retried once. A stable retry may be exact; a
second movement emits `CROSS_SOURCE_COMPOSITION_UNSTABLE`, makes candidate and
affected-filter coverage partial, suppresses public continuation, and forces
cross-source drift/absence to `UNKNOWN`. Stable incomplete progress or pending
updates does not spin and emits its specific partial limitation. Assert call
counts never exceed two composition attempts times two backend attempts.

Represent every relevant before/after observation as
`WorkspaceLaneStamp::Available(stamp)` or `Unavailable(reason)` and cover
available-to-unavailable, unavailable-to-available, and unavailable-reason
changes. A stable unavailable lane is coherent partial evidence and may page
known results; its canonical tag/reason participates in the continuation
digest. Add source-only, script-only, and mixed queries. Prove script-only and
#340 neither open nor validate the Kotlin source index and remain exact while
`.kt` generation/progress/pending changes; prove source-only and mixed queries
retry or become partial for the same mutation. In mixed count output, assert an
exact script group can coexist with a known-minimum source group and overall
count. Git movement is relevant only when dirty filtering/grouping/projection
requests it.

- [ ] **Step 5: Add the root-A/root-B exact-root regression**

In both smoke files, configure root A with only root B's ready descriptor and
source-index database. Invoke:

```console
kast agent workspace-files --workspace-root <root-a> --backend idea
```

Assert the typed exact-root rejection, zero `raw/workspace-files` requests,
and no read/open observation for root B's database. This is a rejected-no-request
test, not merely an output-path assertion.

- [ ] **Step 6: Run the focused red tests**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked workspace_inventory
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_workspace_files_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test semantic_workspace_admission_smoke workspace_files
```

Expected: generation-bound paging, bounded backend/composition restarts,
source-index state, containment, Git, and exact-root cases fail because the
collector is absent.

- [ ] **Step 7: Implement strict backend paging**

Derive the typed source-only/script-only/mixed kind domain before any lane read.
Fetch matching module metadata and its opaque snapshot token, sort exact module
names, and echo both kind domain and snapshot while requesting opaque cursors until each module
returns no next token. Never parse or construct a token in Rust. Validate
only non-repeated handles, non-overlapping physical paths, and cumulative
returned evidence that never exceeds and finally equals the declared module
count. Generation, query/module binding, offsets, expiry, and capacity integrity
belong to server-held typed state. Preserve duplicate physical paths as one
record with a `BTreeSet` of owners.

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
one module. Invalid page state is local only after the snapshot independently
validates; invalid snapshot state or final validation is workspace-wide and
discards all backend pages. Only `STALE_WORKSPACE_INVENTORY` consumes the
single backend restart.

After the last page, use the validation-only raw request to prove the snapshot
lease still names the current backend generation. This validation participates
in the later whole-composition barrier.

- [ ] **Step 8: Implement conservative composition and containment**

Candidate keys come only from backend pages and `.kt` index rows. For index-only
`.kt`, associate all canonical containing module roots; `INDEX_ONLY` requires
every possible owner complete. Unknown or overlapping partial ownership emits
`PROJECT_MODEL_OWNERSHIP_UNKNOWN`. `.kts` never queries the source index and
uses `NotApplicable` states.

Read `.kt` rows only for source-only or mixed domains. Decode indexed Gradle
ownership exclusively from the complete build-root/project-path pair; retain it
as `BuildQualifiedGradleProjectIdentity` and never parse legacy `module_path`.

For every missing candidate, walk to the deepest existing ancestor and
canonicalize it against the admitted canonical root before appending normalized
missing components. Exclude escaping or unprovable paths with
`PATH_CONTAINMENT_UNPROVABLE`; lexical containment alone is insufficient.

- [ ] **Step 9: Implement exact-root Git mapping**

Resolve Git top level, prove containment, run porcelain v2 with
`-c status.relativePaths=false` and `-- .`, and map both current and original
paths from repository root through the exact workspace prefix. Parse records
`1`, `2`, `u`, and `?`. Invalid bytes or mapping failure produces
`DirtyWorkspaceCoverage::Unavailable`.

- [ ] **Step 10: Implement the coherent composition barrier**

Record the kind domain and each lane as relevant-with-purpose or irrelevant;
each relevant lane contains an exact `Available(stamp)` or
`Unavailable(reason)`. After composition, re-observe only relevant lanes and
compare canonical discriminated states, including availability transitions and
reason changes. Publish a coherent snapshot when all relevant states match,
including stable unavailability. Retry the entire composition once after a
change. A second change returns typed partial evidence, never mixed exact
evidence. Stable incomplete index state is partial without retry only for a
selected source partition. Canonically digest kind domain, relevance/purpose,
and lane states for public continuation.

- [ ] **Step 11: Run focused tests green and commit**

Run Step 6, then:

```console
git add cli-rs/src/workspace_inventory.rs cli-rs/src/workspace_inventory cli-rs/tests/support/workspace_files.rs cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/tests/semantic_workspace_admission_smoke.rs
git diff --cached --check
git commit -m "feat: compose exhaustive workspace file evidence"
```

### Task 6: Project bounded public discovery and callable capability evidence

**Files:**

- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/WorkspaceFilesContinuationQuery.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/WorkspaceFilesContinuationAction.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/WorkspaceFilesContinuationResult.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/WorkspaceFilesPublicContinuationState.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/validation/WorkspaceFilesPublicPageToken.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/protocol/InvalidWorkspaceFilesPageTokenException.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/RpcAnalysisDispatcher.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`
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
- Produces: internal issue/consume service for server-held
  `WorkspaceFilesPublicContinuationState`, plus public
  `WorkspaceFilesPublicPageToken`, `INVALID_WORKSPACE_FILES_PAGE_TOKEN`, and
  `STALE_WORKSPACE_FILES_PAGE` failures.
- Reuses: ADR 0020 `AgentResultCardinality::Exact | KnownMinimum` with
  `returnedCount`, `truncated`, and separate typed candidate/filter coverage.

- [ ] **Step 1: Write failing output, filter, limitation, and budget tests**

Assert compact records contain sorted backend owners and structured
build-qualified Gradle project owners, source sets, kind, structured
package evidence, source-index state, drift, dirty state, and paths. Cover each
filter and conjunction; partial pages, unavailable index/Git, invalid package
reference, and both candidate sources unavailable. Seed 500 records and assert
20 default records, at most 120 lines, and at most 1,500 estimated tokens.

Add dispatcher contract tests for public continuation issue/consume. Prove the
server returns only a random handle on issue, consumes it once with exact query
identity, applies the shared TTL/capacity policy, and never serializes stored
state into the token.

Keep Kotlin and Rust public page-token types distinct from raw snapshot/page
handles. `INVALID_WORKSPACE_FILES_PAGE_TOKEN` is non-retryable status 400;
`STALE_WORKSPACE_FILES_PAGE` is retryable status 409 and requires a new unpaged
query rather than automatic restart.

With `--limit 200`, consume returned public tokens and assert page sizes
200/200/100, strictly increasing relative paths, no overlap or gaps, stable
cardinality, per-page `returnedCount`, and no terminal token. Assert a changed
relevant bound backend/index/filesystem/Git composition lane returns
`STALE_WORKSPACE_FILES_PAGE`; malformed, forged, unknown, expired, evicted, or
already-consumed state returns `INVALID_WORKSPACE_FILES_PAGE_TOKEN`; and any
filter, view, field selection, backend, root, or limit mismatch fails rather
than reinterpreting the cursor.

Run the same continuation fixture with stable backend-only and index-only
partial evidence. Assert tokens are issued for further known matches, retain
`KNOWN_MINIMUM`, and bind canonical `Unavailable(reason)` lane state without
requiring a nonexistent stamp. Availability or unavailable-reason changes are
stale. For script-only paging, mutate `.kt` generation/progress/pending between
pages and assert the token remains valid because the index lane is irrelevant;
the same mutations stale source-only and mixed tokens.

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

Also prove incomplete `.kt` progress leaves `--kind script` exact, makes
`--kind source` known-minimum, and gives a mixed count an exact script group
beside a known-minimum source group and overall result.

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
args, derive the normalized kind domain and lane relevance, open one raw
session, collect the uncapped snapshot, then filter. Match the closed
`backend:<name>` or `gradle:<build-root>#<project-path>` selector against its
corresponding owner type, sort by relative path and sorted owner sets, and only
then take `limit`. Compute candidate-inventory and selected-filter evidence
coverage before projection. Do not infer exact match cardinality merely from a
fully consumed known-candidate vector.

For a resumed request, consume the opaque public handle through the internal
server continuation service, require the identical normalized query identity,
recollect through the composition barrier, and compare the full composition-
stamp digest, including kind domain, lane relevance/purpose, and exact
available/unavailable state. Seek strictly after the stored relative path and
verify cumulative returned evidence. Never fall back to page one after invalid
or stale state.

- [ ] **Step 5: Add compact, fields, count, verbose, and explain projections**

Compact and selected views never contain raw envelopes. Count groups known
records by kind/index/drift/dirty, with the same discriminated exactness as the
overall result. Compact and selected pages serialize `cardinality`,
`returnedCount`, `truncated`, and `coverage.candidateInventory` plus
`coverage.filterEvidence`, plus `nextPageToken` only when another known match
exists. Verbose adds per-module page coverage; explain adds
normalized query and classification evidence. Preserve typed backend/index
failures in failed envelopes.

Register continuation state through the shared server-held store only after a
coherent composition and only when more known matches remain. Store exact root,
backend, normalized query/view/limit, composition-stamp digest, last path, and
cumulative returned count; do not store or trust a serialized candidate page.
Coherent partial compositions with stable unavailable lane states may continue
known matches; suppress continuation only for an unstable composition.

- [ ] **Step 6: Implement the route registry and verification intersection**

Use one typed enum-to-backend-capability match. Keep raw capability counts for
diagnosis, but expose public command evidence only through the registry
intersection. #342 extends this owner rather than creating another list.

- [ ] **Step 7: Run focused tests green and commit**

Run Step 3, then:

```console
git add analysis-api analysis-server cli-rs/src/agent.rs cli-rs/src/agent cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/tests/agent_result_projection_smoke.rs
git diff --cached --check
git commit -m "feat: expose bounded workspace file discovery"
```

### Task 7: Regenerate contracts, prove composition, and teach the public path

**Files:**

- Regenerate: `cli-rs/protocol/`
- Modify: `cli-rs/resources/kast-skill/references/commands.json`
- Regenerate: `cli-rs/resources/kast-skill/references/commands.yaml`
- Regenerate: `cli-rs/resources/kast-skill/references/requests/raw/workspace-files/`
- Regenerate: `cli-rs/resources/kast-skill/references/requests/raw/workspace-files-continuation/`
- Modify: `cli-rs/tests/rpc_catalog_smoke.rs`
- Modify: `cli-rs/tests/agent_workspace_files_smoke.rs`
- Modify: `cli-rs/AGENTS.md`
- Modify: `cli-rs/src/agent/AGENTS.md`
- Modify: `cli-rs/resources/kast-skill/AGENTS.md`
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
`snapshotToken`/`pageToken` fields, the internal public-continuation issue/
consume method, and replace the stale capped-secondary description with the
current server-held generation-bound paging contract. The release generator
consumes that JSON; it does not produce it. Refresh the
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
public continuation, partial limitations, backend and cross-source bounded
retries, per-kind lane relevance, discriminated available/unavailable
composition stamps, build-qualified Gradle owner sets, and direct path
composition. State explicitly that `.kts` is not in the Kotlin source index,
unrelated `.kt` progress cannot make script-only discovery partial, and Gradle
semantic declarations arrive with #340.

Update `cli-rs/AGENTS.md` to list `workspace-files` in the public typed command
surface and assign `workspace_inventory` coherence/continuation ownership.
Update `cli-rs/resources/kast-skill/AGENTS.md` to record the public routing and
hand-authored catalog/generated-output boundary. Both guides make package,
LSP, and routing gates below mandatory for workspace-files/catalog/guidance
changes.

- [ ] **Step 4: Update reference and how-to docs**

Document every flag, limit, page token, result view, typed backend/build-
qualified Gradle owner set, package state,
drift/index truth table, limitations, exact-root behavior, server-held raw
paging, exact-once store disposal, kind-relevant coherent cross-source
composition, stable partial continuation, and typed invalid/stale public
continuation. Replace generic-search-first guidance with `workspace-files`, then
diagnostics or exact symbol lookup.

- [ ] **Step 5: Validate guidance and commit**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke --test agent_workspace_files_smoke --test rpc_catalog_smoke
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-kast-routing-evals.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
git add cli-rs/AGENTS.md cli-rs/protocol cli-rs/resources cli-rs/tests/rpc_catalog_smoke.rs cli-rs/tests/agent_workspace_files_smoke.rs cli-rs/src/agent/AGENTS.md docs
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
TTL/capacity/integrity and exact-once disposal, final backend validation,
build-qualified root/included project identity, source-index generation/
progress/pending, and kind-relevant composition-barrier tests pass; `.kts`
remains rejected by `SourceIndexFilePolicyTest`.

- [ ] **Step 3: Run full Gradle and IDEA packaging gates**

```console
./gradlew test --no-daemon
./gradlew buildIdeaPlugin --no-daemon
```

Expected: the complete JVM suite and packaged IDEA plugin pass after the
cross-module continuation lifecycle, schema, and producer changes.

- [ ] **Step 4: Run full Rust quality gates**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
```

- [ ] **Step 5: Prove generated contracts and docs are current**

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
python3 .github/scripts/render-rpc-contract-summary.py --check
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-kast-routing-evals.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```

- [ ] **Step 6: Review scope and whitespace**

```console
git status --short --branch
git diff --stat origin/main...HEAD
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD
```

Expected: issue source/tests/guidance/ADR/spec/plan, the dedicated
build-qualified source-index schema migration, and required generated contracts
only; no `.kts` admission or unrelated source-index schema change.

- [ ] **Step 7: Request independent review**

Ask a fresh reviewer to check generation-bound paging determinism,
equal-cardinality/module-set stale detection, single bounded restart,
cross-module cursor rejection, included-build project-model script authority,
`.kt`-only source-index preservation, multi-owner physical files, nested Git
mapping, exact-root rejected-no-request proof, package-state SQL, false
`INDEX_ONLY`, project-model error/reason mapping, metadata failure, zero-module
global partiality, candidate versus filter coverage, `EXACT` versus
`KNOWN_MINIMUM`, server-held TTL/capacity/integrity and exact-once disposal,
deepest-ancestor missing-path containment, build-qualified root/included Gradle
identity without IDEA fallback promotion, index generation/progress/pending
state, kind-relevant cross-source barrier mutation, available/unavailable lane
digests, backend/index-only partial continuation, public 200/200/100
continuation, invalid/stale/query-mismatched public tokens, hand-authored catalog
ownership, Kotlin type isolation, scoped ownership guidance, package/LSP/routing
gates, capability callability, #340 reuse, full `test`/`buildIdeaPlugin` gates,
budgets, and every issue acceptance criterion. Repair each blocking finding
with a focused red-green commit and rerun the affected gate plus full Rust and
Kotlin suites.
