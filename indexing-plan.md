# Proposal: Simplify and Accelerate Kast Standalone Workspace Discovery and File/Search Operations

## Purpose

This proposal consolidates the current performance findings, Ktor validation results, harness improvements, and proposed optimization work into one audit-ready plan. It is framed so GPT-5.5 Pro can evaluate the plan’s evidence, assumptions, risks, sequencing, and likely return on investment.

The core thesis is:

Kast’s standalone backend should move toward a Gradle-owned workspace model as the primary discovery mechanism, remove regex/static Gradle discovery, and use the existing source index for file/search operations instead of repeated filesystem or PSI-heavy paths.

The plan should be judged on whether it improves large Kotlin/Gradle workspace performance without regressing correctness for Kotlin Multiplatform, included builds, non-standard source-set layouts, and mixed Java/Kotlin projects.

---

## Current Evidence Base

Recent profiling and validation produced several relevant findings.

First, the profiling harness issue around combined `wall,cpu` async-profiler modes was fixed by running each profiler mode sequentially against a fresh daemon. A 3-module and 30-module validation run confirmed that both wall and CPU profiling now produce clean flame graphs, JFR recordings, per-mode telemetry, diagnostics, and RPC latency rows tagged by `profileMode`.

Second, the 30-module synthetic runs consistently identified the slowest JSON-RPC operations after startup as:

`raw/workspace-files`, `raw/diagnostics`, `raw/workspace-search`, and `raw/workspace-symbol`.

The 30-module telemetry showed `kast.workspaceFiles`, `kast.workspaceSearch`, and `kast.diagnostics` as major p95 latency signals. JFR also showed heavy allocation pressure, especially `byte[]`, `String`, collection, Zip, and Kotlin protobuf allocations. GC pause time was noticeable but not obviously the dominant cause.

Third, Ktor 3.2.3 proved to be a useful real-world stress case. It is a large Kotlin-heavy Gradle/KMP workspace with approximately 1,995 `.kt` files, 152 `.kts` files, 122 root settings DSL projects, and 130 `build.gradle.kts` files across root and included builds. It also exposed a standalone discovery gap: many Ktor modules use platform/source-set directories such as `posix/src`, `js/src`, and `wasmJs/src`, rather than only conventional `src/main/kotlin` roots.

Fourth, stock Gradle Tooling API models were insufficient in their current use. `IdeaProject` and `EclipseProject` probing against Ktor returned zero source directories for sampled modules in the problematic cases. This led to the addition of a Gradle-owned Kotlin-style source-set extraction fallback task. The targeted tracer now passes for a fixture where the IDEA model omits source roots and returns source-set roots such as `common/src` and `jvmTest/src`.

Fifth, subsequent Ktor retries uncovered additional constraints: Ktor’s Gradle daemon defaults to `-Xmx9g`, which exceeded local Colima/Docker memory constraints, so the profiling harness now injects bounded Gradle/Kotlin daemon heap settings and worker counts. Ktor also required `-XX:+UseParallelGC`, which was added to the harness Gradle JVM defaults.

Sixth, the current discovery strategy has become complex. It includes static discovery, IDEA model discovery, Gradle source-set extraction fallback, fallback merging, phased enrichment, caching, and conditional preference logic. This complexity increases the number of failure modes and makes performance attribution harder.

---

## Proposal Summary

The proposal is to implement the following prioritized plan:

1. Use `GradleProject` or another Gradle-owned source-set extraction path as the primary workspace discovery mechanism.
2. Fix `workspaceFiles` so metadata-only requests use the source index instead of walking the filesystem.
3. Optimize `workspaceSearch` candidate selection so text searches use indexed source paths before PSI-heavy fallbacks.
4. Remove static Gradle discovery entirely once the Gradle-owned discovery path is proven correct and fast enough.
5. Expand the source index API so file operations can consistently use indexed file metadata and path enumeration.

The proposal intentionally treats the removal of static discovery as dependent on validating the new primary discovery path. Static discovery should not be removed first.

---

# Priority 1: Replace Primary Discovery with a Gradle-Owned Model

## Goal

Make Gradle-owned workspace discovery the primary path for source modules, source sets, and inter-project relationships.

The proposed implementation names this path `loadModulesWithGradleProject()`, using `connection.model(GradleProject::class.java)` instead of the current `IdeaProject`-based path where possible.

However, this plan needs one audit caveat: prior Ktor investigation already showed that stock Tooling API models may omit source roots for KMP layouts. Therefore, GPT-5.5 Pro should scrutinize whether `GradleProject.getSourceSets()` is actually available and sufficient for the target Gradle/Kotlin plugin versions and whether it captures KMP source sets such as `commonMain`, `jvmTest`, `jsMain`, `wasmJsMain`, and custom source-set directories.

If `GradleProject` alone is insufficient, the correct primary path may be a Gradle-owned source-set extraction task, not raw `GradleProject`.

## Proposed Implementation

Add a new discovery path in `GradleWorkspaceDiscovery.kt`:

```kotlin
fun loadModulesWithGradleProject(
    workspaceRoot: Path,
    timeoutMillis: Long,
): List<GradleSourceModule>
```

This path should attempt to extract:

```text
module identity
Gradle project path
main source roots
test source roots
source-set names
inter-project dependencies
included build identity, if available
language/platform metadata, if available
```

Discovery should prefer Gradle-owned source-set data over regex/static parsing.

The path should be configurable during rollout:

```text
discoveryMode = gradleProject | ideaProject | gradleSourceSetTask
```

For the final architecture, the goal should be one primary Gradle-owned path plus cache, not several competing strategies.

## Testable Outcomes

Discovery should be validated against synthetic fixtures and Ktor-like fixtures.

```kotlin
@Test
fun `Gradle-owned discovery extracts source sets from non-standard Kotlin layouts`() {
    val modules = loadModulesWithGradleProject(workspaceRoot, timeoutMillis)

    val app = modules.single { it.gradlePath == ":app" }

    assertTrue(app.mainSourceRoots.any { it.endsWith("common/src") })
    assertTrue(app.testSourceRoots.any { it.endsWith("jvmTest/src") })
}
```

```kotlin
@Test
fun `Gradle-owned discovery preserves inter-project dependencies`() {
    val modules = loadModulesWithGradleProject(workspaceRoot, timeoutMillis)

    val app = modules.single { it.gradlePath == ":app" }

    assertTrue(
        app.dependencies.any {
            it is GradleDependency.ModuleDependency &&
                it.targetIdeaModuleName == ":lib"
        }
    )
}
```

```kotlin
@Test
fun `Gradle-owned discovery handles mixed Java and Kotlin modules`() {
    val modules = loadModulesWithGradleProject(workspaceRoot, timeoutMillis)

    assertTrue(modules.any { it.gradlePath == ":java-only" })
    assertTrue(modules.any { it.gradlePath == ":kmp-lib" })

    val kmp = modules.single { it.gradlePath == ":kmp-lib" }
    assertTrue(kmp.mainSourceRoots.isNotEmpty())
}
```

```kotlin
@Test
fun `Gradle-owned discovery is faster than IdeaProject discovery on representative workspace`() {
    val gradleOwnedTime = measureTimeMillis {
        loadModulesWithGradleProject(workspaceRoot, timeoutMillis)
    }

    val ideaProjectTime = measureTimeMillis {
        loadModulesWithToolingApi(workspaceRoot, timeoutMillis)
    }

    assertTrue(gradleOwnedTime < ideaProjectTime)
}
```

Avoid hard-coding a 50% improvement threshold as the first correctness gate. The initial test should prove that the path is faster. A stricter 50–70% target can be added as a benchmark threshold once repeated local and CI data are available.

## Expected Impact

Expected improvement: 50–70% reduction in workspace discovery time, if `GradleProject` or the Gradle-owned extraction path avoids unnecessary external dependency resolution.

Confidence: medium, not high, until verified against Ktor and another large KMP workspace.

## Audit Questions for GPT-5.5 Pro

Evaluate whether `GradleProject` actually avoids the expensive dependency resolution observed in `IdeaProject`.

Evaluate whether `GradleProject.getSourceSets()` is available and complete for the Gradle and Kotlin Multiplatform versions Kast must support.

Evaluate whether the existing Gradle source-set extraction task is a better primary mechanism than `GradleProject`.

Evaluate whether this plan risks losing external library classpath information needed by downstream symbol resolution, diagnostics, or `workspaceSymbol`.

---

# Priority 2: Fix `workspaceFiles` Metadata-Only Filesystem Walks

## Goal

Make `workspaceFiles(includeFiles=false)` use indexed metadata rather than walking source roots.

Current profiling indicates that `workspaceFiles` is one of the slowest post-startup operations. The suspected cause is filesystem walking per source root even when the request only needs file counts and not file paths.

## Proposed Implementation

Modify `collectWorkspaceFiles()` so that:

```text
includeFiles=false -> use source index file counts
includeFiles=true  -> enumerate indexed paths when possible
fallback           -> filesystem walk only if index unavailable or stale
```

Add telemetry to distinguish:

```text
workspaceFiles.countSource = sourceIndex | filesystemWalk
workspaceFiles.pathSource = sourceIndex | filesystemWalk
workspaceFiles.sourceRootCount
workspaceFiles.fileCount
workspaceFiles.indexReady
```

## Testable Outcomes

```kotlin
@Test
fun `workspaceFiles with includeFiles false uses source index not filesystem`() {
    val filesystemWalkCount = AtomicInteger(0)

    val result = backend.workspaceFiles(
        ParsedWorkspaceFilesQuery(
            includeFiles = false,
            maxFilesPerModule = null,
        )
    )

    assertEquals(0, filesystemWalkCount.get())
    assertTrue(result.modules.sumOf { it.fileCount } > 0)
}
```

```kotlin
@Test
fun `workspaceFiles indexed file counts match enumerated files`() {
    val indexResult = backend.workspaceFiles(
        ParsedWorkspaceFilesQuery(
            includeFiles = false,
            maxFilesPerModule = null,
        )
    )

    val fullResult = backend.workspaceFiles(
        ParsedWorkspaceFilesQuery(
            includeFiles = true,
            maxFilesPerModule = Int.MAX_VALUE,
        )
    )

    assertEquals(
        indexResult.modules.sumOf { it.fileCount },
        fullResult.modules.sumOf { it.files.size },
    )
}
```

```kotlin
@Test
fun `workspaceFiles falls back gracefully when source index is unavailable`() {
    backend.disableSourceIndexForTest()

    val result = backend.workspaceFiles(
        ParsedWorkspaceFilesQuery(
            includeFiles = false,
            maxFilesPerModule = null,
        )
    )

    assertTrue(result.modules.isNotEmpty())
    assertTrue(result.modules.sumOf { it.fileCount } > 0)
}
```

## Expected Impact

Expected improvement: 80–90% reduction for metadata-only `workspaceFiles` requests.

Confidence: high, assuming the source index already has complete file metadata when the RPC runs.

## Audit Questions for GPT-5.5 Pro

Evaluate whether the source index is guaranteed to be ready before `workspaceFiles` is called.

Evaluate whether file counts should include only Kotlin files or all recognized source files.

Evaluate whether deleted files, generated files, symlinks, ignored directories, and stale index entries are handled correctly.

Evaluate whether the fallback path is sufficiently observable through telemetry.

---

# Priority 3: Optimize `workspaceSearch` Candidate Path Selection

## Goal

Avoid loading PSI for text-only search when the source index can provide candidate paths.

Current analysis suggests `workspaceSearch` may call `session.allKtFiles()` before text searching. That can force PSI loading and add unnecessary latency. The source identifier index should be able to provide candidate file paths for many searches.

## Proposed Implementation

Modify `candidateWorkspaceSearchPaths()` to:

```text
1. Ask sourceIdentifierIndex for identifier-based candidate paths when possible.
2. Use indexed source file paths for plain text search when identifier narrowing is not possible.
3. Only fall back to session.allKtFiles() when the index is unavailable, incomplete, stale, or the query type requires PSI.
```

For non-regex, identifier-like patterns, apply the existing identifier extraction logic to restrict candidate files.

For regex or case-insensitive patterns, be conservative. Incorrect pruning is worse than scanning too many files.

## Testable Outcomes

```kotlin
@Test
fun `workspaceSearch does not load PSI when source index is available`() {
    val psiLoadCount = AtomicInteger(0)

    backend.awaitInitialSourceIndex()

    backend.workspaceSearch(
        ParsedWorkspaceSearchQuery(
            pattern = SearchPattern("testFunction"),
            regex = false,
            caseSensitive = false,
        )
    )

    assertEquals(0, psiLoadCount.get())
}
```

```kotlin
@Test
fun `workspaceSearch indexed candidate results match PSI fallback results`() {
    backend.awaitInitialSourceIndex()

    val indexResults = backend.workspaceSearch(query)

    backend.disableSourceIndexForTest()
    val fallbackResults = backend.workspaceSearch(query)

    assertEquals(
        fallbackResults.matches.map { it.location }.toSet(),
        indexResults.matches.map { it.location }.toSet(),
    )
}
```

```kotlin
@Test
fun `workspaceSearch does not incorrectly prune regex searches`() {
    backend.awaitInitialSourceIndex()

    val query = ParsedWorkspaceSearchQuery(
        pattern = SearchPattern("fun\\s+test.*"),
        regex = true,
        caseSensitive = true,
    )

    val indexResults = backend.workspaceSearch(query)

    backend.disableSourceIndexForTest()
    val fallbackResults = backend.workspaceSearch(query)

    assertEquals(
        fallbackResults.matches.map { it.location }.toSet(),
        indexResults.matches.map { it.location }.toSet(),
    )
}
```

## Expected Impact

Expected improvement: 60–80% reduction in `workspaceSearch` latency for identifier-like text searches when the index is available.

Confidence: medium. The correctness risk is higher than Priority 2 because indexed candidate pruning can silently drop valid matches if the query classifier is too aggressive.

## Audit Questions for GPT-5.5 Pro

Evaluate whether indexed candidate filtering is safe for case-insensitive, regex, substring, and symbol-like searches.

Evaluate whether the source index stores enough data to support search candidate narrowing without PSI.

Evaluate whether this optimization should be limited first to exact identifier searches.

Evaluate whether result ordering, limits, and truncation semantics remain unchanged.

---

# Priority 4: Remove Static Discovery Entirely After Gradle-Owned Discovery Is Proven

## Goal

Delete the regex/static Gradle discovery path once the primary Gradle-owned discovery path is correct and fast enough.

The revised position is that static discovery should not remain as a permanent fallback. Static Gradle parsing cannot reliably model Gradle semantics, especially convention plugins, version catalogs, `allprojects`/`subprojects`, included builds, custom source-set directories, and Kotlin Multiplatform layouts.

Ktor already demonstrated that non-standard source-set layouts break assumptions that static discovery is likely to make.

## Proposed Implementation

After Priority 1 is validated:

1. Delete `StaticGradleWorkspaceDiscovery.kt`.
2. Remove `shouldPreferStaticDiscovery` logic from `GradleSettingsSnapshot.kt`.
3. Simplify `GradleWorkspaceDiscovery.discover()`.
4. Remove `discoverPhased()`.
5. Remove `enrichStaticModulesWithToolingApiLibraries()`.
6. Remove `mergeToolingAndStaticModules()`.
7. Remove `staticModulesProvider` parameters from discovery APIs.
8. Update call sites.
9. Preserve cache as an orthogonal optimization, not as a separate discovery strategy.

## Testable Outcomes

```kotlin
@Test
fun `discovery works without static fallback for small projects`() {
    val layout = GradleWorkspaceDiscovery.discover(
        workspaceRoot = smallWorkspaceRoot,
        extraClasspathRoots = emptyList(),
    )

    assertTrue(layout.sourceModules.isNotEmpty())
    assertEquals(expectedModules, layout.sourceModules.map { it.name })
}
```

```kotlin
@Test
fun `discovery works without static fallback for large projects`() {
    val layout = GradleWorkspaceDiscovery.discover(
        workspaceRoot = largeWorkspaceRoot,
        extraClasspathRoots = emptyList(),
    )

    assertTrue(layout.sourceModules.isNotEmpty())
    assertEquals(expectedLargeModuleCount, layout.sourceModules.size)
}
```

```kotlin
@Test
fun `discovery fails observably when Gradle-owned discovery times out`() {
    val exception = assertThrows<TimeoutException> {
        GradleWorkspaceDiscovery.discover(
            workspaceRoot = workspaceRoot,
            extraClasspathRoots = emptyList(),
            toolingApiLoader = { _, _ ->
                Thread.sleep(100_000)
                emptyList()
            },
            config = KastConfig.defaults().copy(
                gradle = KastConfig.GradleConfig.defaults().copy(
                    toolingApiTimeoutMillis = 100L,
                )
            ),
        )
    }

    assertTrue(exception.message!!.contains("Gradle"))
}
```

```kotlin
@Test
fun `primary discovery path does not call static discovery`() {
    val staticDiscoveryCalled = AtomicBoolean(false)

    val layout = GradleWorkspaceDiscovery.discover(
        workspaceRoot = workspaceRoot,
        extraClasspathRoots = emptyList(),
        staticModulesProvider = {
            staticDiscoveryCalled.set(true)
            emptyList()
        },
    )

    assertFalse(staticDiscoveryCalled.get())
    assertTrue(layout.sourceModules.isNotEmpty())
}
```

Once static discovery is deleted, the final test should be removed or replaced with a compile-time/API check proving no static provider exists.

## Expected Impact

Expected impact:

```text
~400 lines of code removed
fewer discovery branches
fewer merge/fallback failure modes
clearer telemetry
simpler debugging
less risk from regex-based Gradle guessing
```

Performance gain from removing merge/phased logic is probably modest, perhaps 10–20% in some cases, but the main value is correctness and maintainability.

Confidence: high for complexity reduction, medium for net runtime improvement.

## Audit Questions for GPT-5.5 Pro

Evaluate whether deleting static discovery creates unacceptable failure behavior in offline, broken-Gradle, or very large workspaces.

Evaluate whether a cache-only emergency mode is needed for cases where Gradle cannot run.

Evaluate whether “fail observably” is preferable to “return partial static guesses.”

Evaluate whether the plan should include a temporary feature flag before deletion.

---

# Priority 5: Expand Source Index APIs for File Operations

## Goal

Make the source index the common substrate for file counts, file enumeration, and source-root mapping.

This should reduce redundant filesystem walks across `workspaceFiles`, `workspaceSearch`, diagnostics, and possibly symbol operations.

## Proposed Implementation

Add source index APIs such as:

```kotlin
fun fileCountBySourceRoot(sourceRoots: Collection<Path>): Map<Path, Int>

fun filesBySourceRoot(
    sourceRoots: Collection<Path>,
    limitPerRoot: Int? = null,
): Map<Path, List<Path>>

fun knownSourcePaths(): Sequence<Path>
```

Use the existing `file_metadata` table if available.

The implementation should define whether counts include:

```text
.kt only
.kts files
.java files
generated files
test files
resources
```

For Kast’s Kotlin-oriented operations, the default should probably be Kotlin source files unless the RPC explicitly asks for broader source inventory.

## Testable Outcomes

```kotlin
@Test
fun `source index file counts match actual filesystem counts`() {
    val indexCounts = session.sqliteStore.fileCountBySourceRoot(sourceRoots)

    val actualCounts = sourceRoots.associateWith { root ->
        Files.walk(root).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.count()
        }
    }

    indexCounts.forEach { (root, count) ->
        assertEquals(actualCounts[root], count)
    }
}
```

```kotlin
@Test
fun `source index file enumeration respects module source roots`() {
    val files = session.sqliteStore.filesBySourceRoot(sourceRoots)

    files.forEach { (root, paths) ->
        assertTrue(paths.all { it.startsWith(root) })
    }
}
```

```kotlin
@Test
fun `source index file operations ignore stale deleted files`() {
    val file = createKotlinFile("src/main/kotlin/Deleted.kt")
    backend.awaitInitialSourceIndex()

    Files.delete(file)
    backend.refreshSourceIndexForTest()

    val files = session.sqliteStore.filesBySourceRoot(sourceRoots)

    assertFalse(files.values.flatten().contains(file))
}
```

## Expected Impact

Expected improvement: elimination of redundant filesystem walks and more consistent file metadata across operations.

Confidence: medium. The impact depends on how many hot paths can safely use index metadata and how reliably the index stays current.

## Audit Questions for GPT-5.5 Pro

Evaluate whether the source index is authoritative enough for file inventory operations.

Evaluate whether there should be a freshness or generation marker on index reads.

Evaluate whether the source index should store source-root ownership directly to avoid repeated path-prefix grouping.

Evaluate whether this introduces consistency bugs when files are created, deleted, or moved during a session.

---

# Proposed Sequencing

The work should not be implemented strictly in numerical order if doing so delays easy wins. A safer sequence is:

1. Land Priority 2 first if the `workspaceFiles` filesystem walk is easy to isolate. This is likely a low-risk, high-confidence optimization.
2. Continue Priority 1 discovery work in parallel because it is the largest strategic change and has the highest correctness risk.
3. Add source index file APIs from Priority 5 as needed to support Priority 2 and Priority 3.
4. Implement Priority 3 after source index semantics are clear.
5. Remove static discovery only after Priority 1 passes correctness and performance gates on synthetic, mixed Java/Kotlin, and Ktor-like workspaces.

This differs slightly from the original plan, which starts with Priority 1. The reason is that `workspaceFiles` appears to be a high-confidence local optimization, while discovery replacement is a larger architectural change.

---

# Acceptance Criteria

The plan should be considered successful only if the following conditions hold.

For discovery:

```text
Ktor-like KMP fixtures discover non-standard source roots.
Mixed Java/Kotlin projects discover both Java and Kotlin modules.
Included builds are either supported or explicitly tested as unsupported.
Discovery is faster than the current IdeaProject path on representative large workspaces.
Failure modes are observable through logs and telemetry.
```

For `workspaceFiles`:

```text
includeFiles=false does not perform filesystem walks when the source index is ready.
Indexed counts match full enumeration.
Fallback behavior works when the source index is unavailable.
Telemetry identifies whether index or filesystem paths were used.
```

For `workspaceSearch`:

```text
Identifier-like searches avoid PSI loading when the source index is ready.
Indexed and fallback results match.
Regex and case-insensitive searches do not lose valid matches.
Result ordering and limit behavior remain stable.
```

For static discovery removal:

```text
No regex/static Gradle discovery remains in the production path.
No phased static/tooling merge logic remains.
The codebase compiles without static provider parameters.
Large and small discovery tests pass without static fallback.
```

---

# Main Strengths of the Plan

The plan is grounded in observed profiling data rather than speculative cleanup.

It targets the operations already identified as slow: `workspaceFiles`, `workspaceSearch`, diagnostics-adjacent indexing behavior, and workspace discovery.

It correctly shifts away from static Gradle parsing, which is structurally weak for modern Gradle/KMP projects.

It aligns with the Ktor findings: Kast needs Gradle-owned source-set extraction, not conventional `src/main/kotlin` assumptions.

It makes the source index more useful as a shared performance substrate.

It keeps tests close to behavior: source-root correctness, dependency preservation, no filesystem walks, no PSI loading, and fallback correctness.

---

# Main Weaknesses and Risks

The largest risk is assuming `GradleProject` is sufficient. Prior Ktor probing already showed that stock Tooling API models can omit source roots. GPT-5.5 Pro should challenge whether `GradleProject` specifically solves that, or whether the Gradle-owned extraction task should become the real primary path.

The second risk is removing static discovery too early. Static discovery is flawed, but it may currently provide partial behavior when Gradle cannot run. Deleting it changes failure behavior from “possibly partial” to “fail.” That may be desirable, but it should be an explicit product decision.

The third risk is search correctness. Index-based candidate pruning can silently omit valid matches, especially for regex, case-insensitive, substring, or non-identifier searches. Priority 3 should start conservatively.

The fourth risk is index freshness. Using the source index for file counts and paths is only correct if the index is ready and current. The plan needs explicit handling for stale, missing, or partially built indexes.

The fifth risk is overfitting to Ktor and the synthetic 30-module workspace. The validation set should include at least one conventional JVM multi-module project, one KMP project, one included-build project, and one mixed Java/Kotlin project.

---

# Recommended Audit Prompt for GPT-5.5 Pro

Use the following prompt to audit the plan:

```text
You are auditing a proposed performance and architecture plan for Kast, a Kotlin/Gradle workspace analysis backend.

Evaluate the plan below for correctness, sequencing, likely performance impact, hidden assumptions, and missing tests. Pay special attention to whether `GradleProject` is actually sufficient for Kotlin Multiplatform source-set discovery, whether static Gradle discovery can be safely removed, and whether source-index-based file/search operations risk stale or incomplete results.

Please produce:
1. A ranked list of the strongest parts of the plan.
2. A ranked list of the weakest assumptions.
3. Specific tests that should be added before implementation.
4. Specific telemetry that should be added to prove or disprove the expected gains.
5. A revised implementation sequence if the current order is risky.
6. A go/no-go judgment for removing static discovery entirely.
7. Any alternative architecture that would better satisfy the same goals.

Evidence available:
- Profiling harness now runs async-profiler wall and CPU modes sequentially with fresh daemons.
- 30-module synthetic runs identify `workspaceFiles`, `diagnostics`, `workspaceSearch`, and `workspaceSymbol` as the slowest post-startup RPCs.
- 30-module telemetry shows `workspaceFiles`, `workspaceSearch`, and `diagnostics` as major p95 latency contributors.
- JFR shows heavy allocation pressure in byte arrays, strings, collections, Zip, and Kotlin protobuf allocations.
- Ktor 3.2.3 is a large Kotlin-heavy Gradle/KMP workspace with non-standard source-set directories such as `posix/src`, `js/src`, and `wasmJs/src`.
- Current standalone discovery failed on Ktor because no source roots were found.
- IdeaProject and EclipseProject probing against Ktor returned zero source directories for sampled modules.
- A Gradle-owned source-set extraction fallback task was added and a targeted tracer now passes for omitted IDEA roots.
- The current discovery stack includes static discovery, IDEA model discovery, Gradle source-set task fallback, merge logic, phased enrichment, and cache.
- The proposal is to make Gradle-owned discovery primary, optimize `workspaceFiles` and `workspaceSearch` through the source index, and delete static discovery after validation.
```

---

# Recommended Final Decision Frame

The plan should be approved with modifications, not accepted exactly as originally written.

The strongest near-term work is Priority 2, because replacing metadata-only filesystem walks with source-index reads is likely to produce a clear win with low architectural risk.

Priority 1 is strategically correct, but the implementation should not assume raw `GradleProject` is sufficient until proven against KMP fixtures and Ktor. The proposal should be reframed as “Gradle-owned discovery” rather than specifically “GradleProject discovery.”

Priority 4 should remain conditional. Static discovery should be removed only after the Gradle-owned path passes correctness and performance gates across representative projects. Once those gates pass, deleting static discovery is likely the right call because regex-based Gradle parsing is not a reliable foundation.

The most important audit question is:

Does the new Gradle-owned discovery path produce complete, correct source-root and dependency data for real Kotlin Multiplatform workspaces without reintroducing the dependency-resolution cost that made `IdeaProject` too slow?

Everything else in the plan depends on that answer.
