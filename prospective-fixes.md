
## Priority 1: Switch to `GradleProject` for Primary Discovery

**Justification**: Your JFR analysis shows workspace discovery is the primary bottleneck. The current `IdeaProject` model resolves all external dependencies, which is unnecessary for internal symbol resolution. `GradleProject` provides project structure and source sets without external library resolution.

**Implementation**:
- Add new function `loadModulesWithGradleProject()` in `GradleWorkspaceDiscovery.kt` that uses `connection.model(GradleProject::class.java)` instead of `IdeaProject::class.java` [1-cite-0](#1-cite-0) 
- Extract source sets directly from `GradleProject.getSourceSets()` API
- Build inter-project dependency graph from `GradleProject.getChildren()` relationships
- Add configuration flag to choose between `GradleProject` (fast) and `IdeaProject` (complete) modes

**Testable Outcomes**:
```kotlin
// RED: Current baseline test
@Test
fun `GradleProject discovery is faster than IdeaProject discovery`() {
    val gradleProjectTime = measureTimeMillis {
        loadModulesWithGradleProject(workspaceRoot, timeoutMillis)
    }
    val ideaProjectTime = measureTimeMillis {
        loadModulesWithToolingApi(workspaceRoot, timeoutMillis)
    }
    assertTrue(gradleProjectTime < ideaProjectTime * 0.5) // 50% faster target
}

// RED: Verify source set correctness
@Test
fun `GradleProject discovery extracts correct source sets`() {
    val modules = loadModulesWithGradleProject(workspaceRoot, timeoutMillis)
    val appModule = modules.find { it.gradlePath == ":app" }
    assertNotNull(appModule)
    assertEquals(expectedMainSourceRoots, appModule.mainSourceRoots)
    assertEquals(expectedTestSourceRoots, appModule.testSourceRoots)
}

// RED: Verify inter-project dependencies
@Test
fun `GradleProject discovery preserves inter-project dependencies`() {
    val modules = loadModulesWithGradleProject(workspaceRoot, timeoutMillis)
    val appModule = modules.find { it.gradlePath == ":app" }
    assertTrue(appModule.dependencies.any { 
        it is GradleDependency.ModuleDependency && 
        it.targetIdeaModuleName == ":lib" 
    })
}
```

**Expected Impact**: 50-70% reduction in workspace discovery time for large projects, based on eliminating external dependency resolution.

---

## Priority 2: Fix `workspaceFiles` Filesystem Walk Hotspot

**Justification**: Your JFR shows `workspaceFiles` does filesystem walks per source root even when `includeFiles=false` [1-cite-1](#1-cite-1) . The source index already tracks file counts.

**Implementation**:
- Modify `collectWorkspaceFiles()` to use `sourceIdentifierIndex.knownPaths()` for file counts when `includeFiles=false`
- Only walk filesystem when `includeFiles=true` and file paths are actually needed
- Add telemetry to track filesystem walk vs index lookup paths

**Testable Outcomes**:
```kotlin
// RED: Verify no filesystem walk when includeFiles=false
@Test
fun `workspaceFiles with includeFiles=false uses source index not filesystem`() {
    val filesystemWalkCount = AtomicInteger(0)
    // Mock or instrument Files.walk to count calls
    
    val result = backend.workspaceFiles(
        ParsedWorkspaceFilesQuery(includeFiles = false, maxFilesPerModule = null)
    )
    
    assertEquals(0, filesystemWalkCount.get())
    assertTrue(result.modules.sumOf { it.fileCount } > 0)
}

// RED: Verify correctness of file counts
@Test
fun `workspaceFiles file counts match source index`() {
    val indexResult = backend.workspaceFiles(
        ParsedWorkspaceFilesQuery(includeFiles = false, maxFilesPerModule = null)
    )
    val walkResult = backend.workspaceFiles(
        ParsedWorkspaceFilesQuery(includeFiles = true, maxFilesPerModule = Int.MAX_VALUE)
    )
    
    assertEquals(
        indexResult.modules.sumOf { it.fileCount },
        walkResult.modules.sumOf { it.fileCount }
    )
}
```

**Expected Impact**: 80-90% reduction in `workspaceFiles` latency when `includeFiles=false` (metadata-only queries).

---

## Priority 3: Optimize `workspaceSearch` Candidate Path Selection

**Justification**: `workspaceSearch` forces PSI loading via `session.allKtFiles()` before text searching [1-cite-2](#1-cite-2) . The source identifier index can provide candidate paths without PSI overhead.

**Implementation**:
- Modify `candidateWorkspaceSearchPaths()` to query `sourceIdentifierIndex.identifierPaths()` first
- Only fall back to `session.allKtFiles()` when source index is unavailable or incomplete
- Add identifier-based filtering for case-sensitive searches using the existing identifier regex logic

**Testable Outcomes**:
```kotlin
// RED: Verify PSI not loaded for text-only searches
@Test
fun `workspaceSearch does not load PSI when source index is available`() {
    val psiLoadCount = AtomicInteger(0)
    // Instrument session.allKtFiles() to count calls
    
    backend.awaitInitialSourceIndex()
    backend.workspaceSearch(
        ParsedWorkspaceSearchQuery(
            pattern = SearchPattern("testFunction"),
            regex = false,
            caseSensitive = false
        )
    )
    
    assertEquals(0, psiLoadCount.get())
}

// RED: Verify search correctness with index-based candidates
@Test
fun `workspaceSearch results match with and without PSI loading`() {
    backend.awaitInitialSourceIndex()
    
    val indexResults = backend.workspaceSearch(query)
    // Force PSI path by clearing or disabling index
    val psiResults = backend.workspaceSearch(query)
    
    assertEquals(indexResults.matches.size, psiResults.matches.size)
}
```

**Expected Impact**: 60-80% reduction in `workspaceSearch` latency for text searches when source index is available.

---

## Priority 4 (Revised): Remove Static Discovery Entirely

**Justification**: Static discovery is ~316 lines of regex-based parsing that fundamentally cannot handle Gradle's dynamic semantics (convention plugins, version catalogs, `allprojects`/`subprojects` blocks). Your Ktor testing proved it breaks on non-standard layouts. With Priority 1's `GradleProject` approach being significantly faster than `IdeaProject`, the "fast but incomplete" fallback becomes unnecessary complexity.

**Implementation**:
1. **Delete `StaticGradleWorkspaceDiscovery.kt` entirely** - Remove all 316 lines of regex-based parsing
2. **Remove `shouldPreferStaticDiscovery` logic** from `GradleSettingsSnapshot.kt` [2-cite-0](#2-cite-0) 
3. **Simplify `discover()` function** - Remove the static/tooling branching logic [2-cite-1](#2-cite-1) 
4. **Remove `discoverPhased()` entirely** - The phased approach was only needed for static discovery's fast-but-incomplete results
5. **Remove `enrichStaticModulesWithToolingApiLibraries()` and `mergeToolingAndStaticModules()`** - No longer needed without static discovery
6. **Remove `staticModulesProvider` parameter** from all discovery functions
7. **Update all call sites** to use the simplified discovery API

**Testable Outcomes**:
```kotlin
// RED: Verify discovery still works for small projects
@Test
fun `discovery works without static fallback for small projects`() {
    val layout = GradleWorkspaceDiscovery.discover(
        workspaceRoot = smallWorkspaceRoot,
        extraClasspathRoots = emptyList(),
    )
    assertTrue(layout.sourceModules.isNotEmpty())
    assertEquals(expectedModules, layout.sourceModules.map { it.name })
}

// RED: Verify discovery works for large projects (the previous static case)
@Test
fun `discovery works without static fallback for large projects`() {
    val layout = GradleWorkspaceDiscovery.discover(
        workspaceRoot = largeWorkspaceRoot, // >200 modules
        extraClasspathRoots = emptyList(),
    )
    assertTrue(layout.sourceModules.isNotEmpty())
    // Verify all modules discovered, not just static subset
    assertEquals(expectedLargeModuleCount, layout.sourceModules.size)
}

// RED: Verify tooling API failure is handled gracefully
@Test
fun `discovery fails gracefully when tooling API times out`() {
    assertThrows<TimeoutException> {
        GradleWorkspaceDiscovery.discover(
            workspaceRoot = workspaceRoot,
            extraClasspathRoots = emptyList(),
            toolingApiLoader = { _, _ -> 
                Thread.sleep(100_000) // Simulate timeout
                emptyList()
            },
            config = KastConfig.defaults().copy(
                gradle = KastConfig.GradleConfig.defaults().copy(
                    toolingApiTimeoutMillis = 100L
                )
            )
        )
    }
}

// RED: Verify GradleProject is used (not IdeaProject)
@Test
fun `discovery uses GradleProject model not IdeaProject`() {
    val modelUsed = AtomicReference<String>()
    GradleWorkspaceDiscovery.discover(
        workspaceRoot = workspaceRoot,
        extraClasspathRoots = emptyList(),
        toolingApiLoader = { root, timeout ->
            // This would be the new loadModulesWithGradleProject
            modelUsed.set("GradleProject")
            loadModulesWithGradleProject(root, timeout)
        }
    )
    assertEquals("GradleProject", modelUsed.get())
}
```

**Expected Impact**:
- **Code reduction**: ~400 lines removed (StaticGradleWorkspaceDiscovery + merge logic + phased discovery)
- **Complexity elimination**: Single discovery path, no merging strategies, no fallback modes
- **Correctness**: No more regex-based guessing at Gradle semantics
- **Performance**: With Priority 1's `GradleProject` approach, even large projects should be fast enough without the static fallback

---

## Priority 5: Leverage Source Index for File Operations

**Justification**: The source index already tracks file metadata [1-cite-4](#1-cite-4) . File operations should query this instead of filesystem walks.

**Implementation**:
- Add `sourceIndex.fileCountBySourceRoot()` method using the `file_metadata` table
- Modify `workspaceFiles` to use this for per-module file counts
- Add `sourceIndex.filesBySourceRoot()` for path enumeration when needed

**Testable Outcomes**:
```kotlin
// RED: Verify file counts from index match filesystem
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

**Expected Impact**: Eliminates redundant filesystem walks, provides consistent file metadata across operations.

---

## Summary of Expected Performance Gains

| Priority | Operation | Expected Improvement | Confidence |
|----------|-----------|---------------------|------------|
| 1 | Workspace discovery | 50-70% faster | High |
| 2 | `workspaceFiles` (metadata) | 80-90% faster | High |
| 3 | `workspaceSearch` (text) | 60-80% faster | Medium |
| 4 | Code complexity | Maintenance improvement | High |
| 5 | File operations | Eliminate redundant walks | Medium |

The TDD approach allows you to validate each optimization independently while maintaining correctness. Start with Priority 1 as it addresses your primary bottleneck identified in JFR analysis.
