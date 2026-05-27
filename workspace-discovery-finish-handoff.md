# Workspace Discovery Finish Handoff

## Objective

Finish the `feature/workspace-discovery-improvements` work so standalone Kast
uses a constrained Gradle-owned workspace representation by default, avoids
filesystem and PSI-heavy paths when the source index can answer the request, and
passes the focused correctness plus performance validation gates.

This is an implementation handoff for a new agent. It is intentionally written
as a TDD sequence, not a retrospective. Preserve the current branch intent, but
do not preserve implementation details that contradict the finish criteria.

## Current State

Branch:

```bash
feature/workspace-discovery-improvements
```

Observed working tree when this plan was written:

```text
## feature/workspace-discovery-improvements...origin/feature/workspace-discovery-improvements [ahead 1]
A  .agents/marketplaces.md
?? prospective-fixes.md
```

Do not overwrite or revert those existing changes unless the user explicitly
asks. Add implementation commits on top.

Important current evidence:

- `prospective-fixes.md` names the original target: replace primary
  `IdeaProject` discovery with a faster Gradle-owned representation, use the
  source index for file/search operations, and remove static discovery.
- Current standalone discovery still imports and calls `IdeaProject` in
  `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/workspace/GradleWorkspaceDiscovery.kt`.
- There is no production `GradleProject` model use in the standalone discovery
  package. The only current `GradleProject` string hits are path-normalization
  helper names in `GradleSettingsSnapshot.kt`.
- Static discovery has already been removed from the current branch.
- `workspaceSearch` already has an index-backed path in
  `StandaloneAnalysisBackend.candidateWorkspaceSearchPaths`.
- `workspaceFiles` already calls
  `SqliteSourceIndexStore.fileCountBySourceRoot`, but that method currently
  derives counts by enumerating `knownSourcePaths()`, not by a constrained SQL
  grouped count.
- Last observed standalone performance run failed:

```text
./gradlew :backend-standalone:test -PincludeTags=performance

PerformanceBaselineTest > lock contention during concurrent reads and Phase 2 writes() FAILED
Write events overlapping reads (705) is unreasonably high
```

## Non-Negotiable Finish Criteria

The work is not complete until all of these are true:

1. Default standalone Gradle discovery no longer loads `IdeaProject` as the
   primary path.
2. The default path uses a constrained Gradle-owned representation that proves
   source roots, source-set buckets, output roots, and project dependency edges
   without external dependency resolution.
3. If a complete external-library classpath path remains, it is explicitly
   opt-in and typed, not hidden in the default discovery flow.
4. `workspaceFiles(includeFiles=false)` obtains counts without filesystem walks
   and without full indexed-file enumeration.
5. `workspaceFiles(includeFiles=true)` uses bounded indexed path enumeration
   when the source index is ready, falling back to filesystem walks only when
   the index is unavailable or unusable.
6. `workspaceSearch` has public-behavior tests proving indexed and fallback
   results agree for representative queries.
7. Static Gradle discovery remains deleted.
8. The standalone performance suite passes, including the lock-contention test.
9. A Dockerized profile run produces artifacts showing READY state, expected
   module counts, index-backed `workspaceFiles`, and startup/RPC latency data.

## Design Boundary

The phrase "GradleProject" in `prospective-fixes.md` should be treated as a
hypothesis, not an instruction to force the stock Tooling API `GradleProject`
model if it lacks source-set truth.

The real design requirement is:

```text
Use the narrowest Gradle-owned model that accurately represents source sets and
project edges for Kotlin/JVM, Java, and Kotlin Multiplatform workspaces without
resolving external dependencies by default.
```

Acceptable primary representations:

- Stock Tooling API `GradleProject`, only if a tracer proves it exposes the
  needed source-set roots for the supported Gradle/Kotlin plugin versions.
- The current Gradle init-script source-set JSON task, promoted to the primary
  constrained loader, if stock `GradleProject` lacks source-set data.

Unacceptable primary representation:

- `IdeaProject`, because it resolves too much and was the original bottleneck.
- Static regex Gradle discovery, because it cannot model real Gradle semantics.

## TDD Implementation Sequence

### Phase 0: Reproduce The Current RED State

Run first and save output:

```bash
./gradlew :backend-standalone:test -PincludeTags=performance 2>&1 | tee /tmp/kast-standalone-performance-red.log
```

Expected current result:

- One failing performance test:
  `PerformanceBaselineTest.lock contention during concurrent reads and Phase 2 writes`.
- Use `backend-standalone/build/test-results/test/TEST-io.github.amichne.kast.standalone.PerformanceBaselineTest.xml`
  as the structured evidence source.

Do not start broad refactors before this RED state is understood.

### Phase 1: Prove The Constrained Gradle Representation

Goal:

Pick the real primary model based on evidence.

Add tracer tests to
`backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/GradleWorkspaceDiscoveryTest.kt`
or `StandaloneWorkspaceDiscoveryTest.kt`.

Tests to add one at a time:

1. `constrained Gradle discovery is the default loader`
   - Inject a loader spy into `GradleWorkspaceDiscovery.discover`.
   - Prove default mode calls the constrained loader.
   - Prove complete mode, if retained, calls the `IdeaProject` loader.

2. `constrained Gradle discovery extracts Kotlin style source set roots`
   - Use the existing Ktor-like fixture pattern where IDEA model roots are
     absent or incomplete.
   - Prove roots such as `common/src` and `jvmTest/src` are captured.

3. `constrained Gradle discovery preserves project dependency edges`
   - Fixture: `:app` depends on `:lib`.
   - Prove `GradleDependency.ModuleDependency(targetIdeaModuleName = ":lib")`
     or equivalent typed edge survives into source module dependencies.

4. `constrained Gradle discovery handles mixed Java and Kotlin modules`
   - Fixture should include a Java module and a KMP-style Kotlin module.
   - Prove the Java module does not mask missing Kotlin source roots.

Implementation shape:

- Introduce a typed discovery mode, for example:

```kotlin
enum class GradleDiscoveryMode {
    CONSTRAINED,
    COMPLETE,
}
```

- Add config field:

```text
[gradle]
discoveryMode = "constrained"
```

- Add `GradleDiscoveryMode` parsing at the config boundary.
- Do not pass raw strings through discovery internals.
- Prefer package-local internal types in
  `backend-standalone/.../workspace/` unless the mode must be public config.

Suggested loader names:

```kotlin
loadModulesWithConstrainedGradleModel(...)
loadModulesWithIdeaProject(...)
```

If stock `GradleProject` is insufficient, implement
`loadModulesWithConstrainedGradleModel` using the existing init-script
source-set model. That still satisfies the architecture if it avoids external
dependency resolution and is the default.

Targeted proof command:

```bash
./gradlew :backend-standalone:test \
  --tests 'io.github.amichne.kast.standalone.GradleWorkspaceDiscoveryTest' \
  --tests 'io.github.amichne.kast.standalone.StandaloneWorkspaceDiscoveryTest' \
  -PexcludeTags=performance
```

### Phase 2: Remove `IdeaProject` From The Default Path

Goal:

Make default discovery constrained and make `IdeaProject` opt-in only.

Implementation tasks:

1. Rename the current `loadModulesWithToolingApi` to make its behavior honest,
   for example `loadModulesWithIdeaProject`.
2. Introduce a constrained loader that does not call
   `connection.model(IdeaProject::class.java)`.
3. Route `GradleWorkspaceDiscovery.discover` by typed mode.
4. Keep failure messages explicit:
   - constrained loader timeout
   - constrained loader no modules
   - complete loader timeout
5. Preserve workspace discovery cache semantics. Cache keys must include the
   discovery mode or a model-version discriminator so complete-mode results do
   not contaminate constrained-mode results.

Acceptance search:

```bash
kast rpc '{"jsonrpc":"2.0","method":"raw/workspace-search","params":{"pattern":"IdeaProject","regex":false,"maxResults":20,"fileGlob":"backend-standalone/src/main/kotlin/**/*.kt","caseSensitive":true},"id":1}' --workspace-root=/Users/amichne/code/kast
```

Expected after implementation:

- `IdeaProject` may remain only in the opt-in complete loader.
- Default tests must prove constrained mode is selected without relying on a
  text search alone.

### Phase 3: Make Source-Index File Inventory Truly Constrained

Goal:

Stop `workspaceFiles(includeFiles=false)` from enumerating all indexed files.

Add RED tests in
`index-store/src/test/kotlin/io/github/amichne/kast/indexstore/SqliteSourceIndexStoreTest.kt`.

Tests:

1. `source file counts are grouped by source root without loading source paths`
   - Prefer a test seam around SQL/query behavior if available.
   - If no seam exists, add a small internal helper whose behavior can be
     tested without filesystem walking.

2. `files by source root honors per-root limit`
   - Prove path enumeration remains bounded for `includeFiles=true`.

Implementation target:

`index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt`

Expected new API shape:

```kotlin
fun fileCountBySourceRoot(sourceRoots: Collection<Path>): Map<Path, Int>
fun filesBySourceRoot(sourceRoots: Collection<Path>, limitPerRoot: Int? = null): Map<Path, List<Path>>
```

But `fileCountBySourceRoot` should execute a count-oriented SQLite query over
manifest/path metadata. It should not call `filesBySourceRoot(...).size`.

Then update:

`backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisBackend.kt`

Expected behavior:

- `includeFiles=false`: fetch counts only.
- `includeFiles=true`: fetch bounded paths and counts.
- fallback: filesystem walk only when index unavailable or query fails.

Targeted proof command:

```bash
./gradlew :index-store:test \
  --tests 'io.github.amichne.kast.indexstore.SqliteSourceIndexStoreTest' \
  -PexcludeTags=performance
```

### Phase 4: Complete Workspace Search Equivalence Tests

Goal:

Turn the current index-backed `workspaceSearch` improvement into a contract.

Add tests in:

`backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisBackendWorkspaceSearchTest.kt`

Tests:

1. `workspace search uses source index without loading all Kotlin files`
   - This exists now. Keep it.

2. `workspace search indexed and fallback results agree`
   - Run the same query with index ready and with index unavailable.
   - Compare file path, line number, column number, and preview.

3. `workspace search regex uses indexed workspace inventory when ready`
   - Regex cannot use identifier narrowing, but it can still use known indexed
     workspace paths instead of PSI.

4. `workspace search falls back when source index is unavailable`
   - Prove behavior remains correct before initial index readiness.

Targeted proof command:

```bash
./gradlew :backend-standalone:test \
  --tests 'io.github.amichne.kast.standalone.StandaloneAnalysisBackendWorkspaceSearchTest' \
  -PexcludeTags=performance
```

### Phase 5: Fix The Lock-Contention Performance Failure

Goal:

Do not relax the performance threshold until evidence shows the test is wrong.

Start by reading:

- `backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/PerformanceBaselineTest.kt`
- `backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/InstrumentedSessionLock.kt`
- `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisSession.kt`

Likely branches:

1. If phase-2 indexing does too many write-lock acquisitions, batch work or
   move non-mutating work outside the write lock.
2. If the instrumentation counts benign write events as overlapping reads, fix
   the metric and add a regression test for the measurement semantics.
3. If reads are taking a write lock through a helper path, split the helper into
   read-safe and write-required sections.

Targeted RED/GREEN command:

```bash
./gradlew :backend-standalone:test \
  --tests 'io.github.amichne.kast.standalone.PerformanceBaselineTest.lock contention during concurrent reads and Phase 2 writes' \
  -PincludeTags=performance
```

Completion requires:

- `max_write_hold_nanos` remains below budget.
- `write_events_overlapping_reads` is within the test threshold.
- The fix is explained by lock ownership, not by threshold relaxation.

### Phase 6: Validate In Widening Rings

Run these in order:

```bash
./gradlew :backend-standalone:test :index-store:test -PexcludeTags=performance
```

```bash
./gradlew :backend-standalone:test -PincludeTags=performance
```

```bash
./gradlew :backend-intellij:test -PincludeTags=performance
```

If config contract files changed, also run the relevant API tests:

```bash
./gradlew :analysis-api:test -PexcludeTags=performance
```

If the branch changes packaging or workflow files:

```bash
.github/scripts/test-standalone-profile-harness-contract.sh
.github/scripts/test-standalone-profile-workflow-contract.sh
```

### Phase 7: Produce Performance Evidence

Smoke profile first:

```bash
scripts/profile-standalone-large-repo.sh \
  --target synthetic-kotlin \
  --modules 3 \
  --duration 1 \
  --profile-modes wall \
  --skip-backend-build \
  --skip-docker-build \
  --run-id codex-synthetic-3-final-smoke
```

Then real evidence:

```bash
scripts/profile-standalone-large-repo.sh \
  --target synthetic-kotlin \
  --modules 240 \
  --duration 30 \
  --profile-modes wall,cpu \
  --skip-backend-build
```

Then Ktor:

```bash
scripts/profile-standalone-large-repo.sh \
  --target ktor \
  --duration 30 \
  --profile-modes wall,cpu \
  --skip-backend-build
```

For each run, inspect:

```bash
jq '{targetLabel, counts, startup, profileModes}' .benchmarks/standalone-profile/results/<run-id>/summary.json
jq -r '[.operation, .method, (.durationMillis|tostring), (.ok|tostring)] | @tsv' .benchmarks/standalone-profile/results/<run-id>/rpc-latencies.jsonl
jq -r 'select(.name=="kast.workspaceFiles") | [.durationNanos, .attributes] | @json' .benchmarks/standalone-profile/results/<run-id>/telemetry/standalone-spans-*.jsonl
```

Evidence must show:

- READY state without timeout.
- Expected module count.
- `kast.workspaceFiles.countSource = sourceIndex` for indexed requests.
- `kast.workspaceFiles.pathSource = sourceIndex` when `includeFiles=true` and
  the index is ready.
- Startup and RPC latencies recorded in saved artifacts.

## Files To Touch

Expected implementation files:

- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/ConfigurationFieldDecoder.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationField.kt`
- new field file under
  `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/`
- `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/workspace/GradleWorkspaceDiscovery.kt`
- `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/workspace/GradleSettingsSnapshot.kt`
- `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisBackend.kt`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt`

Expected tests:

- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/KastConfigTest.kt`
- `backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/GradleWorkspaceDiscoveryTest.kt`
- `backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/StandaloneWorkspaceDiscoveryTest.kt`
- `backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisBackendWorkspaceSearchTest.kt`
- `backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/PerformanceBaselineTest.kt`
- `index-store/src/test/kotlin/io/github/amichne/kast/indexstore/SqliteSourceIndexStoreTest.kt`

Docs/config surfaces if the user wants the config field public:

- `docs/architecture/profiling.md`
- `docs/openapi.yaml` only if the RPC/config contract generator expects config
  documentation there.
- `.github/workflows/standalone-profile.yml` only if profile validation inputs
  need to expose the new discovery mode.

## How To Work

Follow this loop:

1. Add one failing tracer test.
2. Implement the smallest behavior needed.
3. Run the targeted command.
4. Refactor only while green.
5. Broaden validation when crossing module boundaries.

Use `kast rpc raw/workspace-search` or native `kast_*` tools for Kotlin semantic
searches. `rg` is fine for non-Kotlin file discovery and shell/documentation
work, but do not use text search as proof of symbol identity or call hierarchy.

## Kotlin Correctness Scorecard

Before final handoff, score these:

- Domain fidelity: discovery mode and source-set model are typed.
- Boundary parsing: config string is parsed once into a constrained mode.
- Layout cohesion: Gradle discovery types stay in the `workspace` package unless
  public config requires `analysis-api`.
- Error design: loader failures distinguish timeout, unsupported model, no
  modules, and incomplete source roots.
- State safety: cache keys prevent cross-mode contamination.
- Test value: tests prove public discovery/search/file behavior, not helper
  implementation trivia.
- Kotlin idiom: implementation uses immutable model values and confined
  mutation only for Gradle/init-script adapters.

No dimension may be `Fail` at completion.

## Handoff Summary For The Next Agent

Start at Phase 0. The first implementation decision is not "use
`GradleProject` no matter what." The first decision is to prove the narrowest
Gradle-owned representation that can handle Ktor/KMP source-set truth without
default external dependency resolution. Once that is proven, make it the
default, move `IdeaProject` behind an explicit complete mode, tighten
source-index file inventory to SQL-level counts, finish workspace search
equivalence tests, and keep working until standalone performance is green.
