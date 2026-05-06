Implement 6 performance/concurrency improvements in the Kast repository, following TDD: write failing tests first, then implement changes to make them pass. The project follows the convention documented in `.github/copilot-instructions.md`: "TDD: write failing unit tests first."

## Repository: michne/kast

---

## Change 1: Parallelize ReferenceIndexer

### Test file (create):
`index-store/src/test/kotlin/io/github/amichne/kast/indexstore/ParallelReferenceIndexerTest.kt`

Write tests that assert:
1. `indexReferences` with `parallelism > 1` processes files concurrently within a batch (use AtomicInteger to track max concurrent scans)
2. Results are identical whether parallelism=1 or parallelism=4
3. Cancellation still works correctly with parallel scanning
4. Scanner exceptions on one file don't corrupt results from parallel files

### Implementation file:
`index-store/src/main/kotlin/io/github/amichne/kast/indexstore/indexing/ReferenceIndexer.kt`

Add a `parallelism: Int = 1` constructor parameter. When `parallelism > 1`, use a dedicated `ExecutorService` (bounded thread pool) to process files within each batch concurrently. The scanning phase (calling `referenceScanner`) runs in parallel, but the SQLite write (`store.replaceReferencesFromFiles`) remains sequential after collecting all batch results. This preserves the existing invariant that SQLite writes are short and serialized.

```kotlin
class ReferenceIndexer(
    private val store: SqliteSourceIndexStore,
    private val batchSize: Int = DEFAULT_REFERENCE_BATCH_SIZE,
    private val parallelism: Int = 1, // NEW
)
```

When parallelism > 1, replace `batch.mapNotNull { ... }` with a parallel executor pattern using `Executors.newFixedThreadPool(parallelism)` with thread name prefix "kast-ref-indexer-".

### Also update:
- `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/BackgroundIndexer.kt` — pass `parallelism` from config to `ReferenceIndexer`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/` — add `IndexingPhase2Parallelism` config field with default=2

---

## Change 2: Decouple Phase 2 from Foreground Read/Write Lock

### Test file (create):
`backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/Phase2ReadStarvationTest.kt`

Tag with `@Tag("concurrency")`. Write tests that assert:
1. Foreground reads (`withReadAccess`) complete within 500ms while Phase 2 is actively scanning (use a slow referenceScanner that sleeps 100ms per file)
2. Phase 2 still produces correct results after decoupling
3. Phase 2 yields to foreground operations (doesn't hold exclusive access for entire batch)

### Implementation:
`backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisSession.kt`

Change Phase 2's locking strategy from holding the write lock for the entire scan to acquiring it only briefly per-file (or per small sub-batch). The key insight from the existing comment is that K2 FIR lazy resolution isn't thread-safe for concurrent resolution within a single session. The fix is to:

1. Have Phase 2 acquire the write lock only for the duration of a single file's `referenceScanner` call (not the entire batch)
2. Between files, release the lock so foreground reads can proceed
3. Add a `tryWriteWithTimeout` method to `SessionLock` that Phase 2 uses — if it can't acquire within N ms, it skips that attempt and retries later

Modify `StandaloneReferenceIndexEnvironment.withExclusiveAccess` to use short-duration lock acquisition with yielding between files. The `ReferenceIndexer` already processes files one at a time within a batch, so the lock can be acquired/released per file.

Also update `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/SessionLock.kt` (or wherever the interface is defined) to add:
```kotlin
fun tryWrite(timeoutMillis: Long, action: () -> T): T?
```

---

## Change 3: Per-File Timeout in findReferenceLocations

### Test file (create):
`backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/PerFileReferenceScanBudgetTest.kt`

Tag with `@Tag("concurrency")`. Write tests that assert:
1. A file whose PSI walk exceeds the per-file budget is skipped (returns empty references for that file)
2. Other files in the candidate set still produce results
3. The total `findReferences` call completes within a reasonable bound even with one pathological file
4. Telemetry records which files were skipped due to timeout

### Implementation:
`backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisBackend.kt`

In the `findReferenceLocations` extension function (around line 602), wrap the PSI walk in a time-bounded check. Add a `perFileScanBudgetMillis` parameter (default 5000ms from config). Use `System.nanoTime()` to check elapsed time periodically during the walk (e.g., every 100 elements) and abort early if exceeded.

Add to `ServerLimits`:
```kotlin
val perFileScanBudgetMillis: Long = 5_000
```

Or add a new config field in `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/`.

The `PsiRecursiveElementWalkingVisitor.visitElement` override should check the deadline and call `stopWalking()` when exceeded.

---

## Change 4: Dedicated Thread Pool for parallelMapFlat

### Test file (create):
`backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/DedicatedParallelPoolTest.kt`

Tag with `@Tag("concurrency")`. Write tests that assert:
1. `parallelMapFlat` uses threads named with "kast-parallel-" prefix (not ForkJoinPool.commonPool)
2. Concurrent `findReferences` calls don't starve each other (measure latency under load)
3. The pool is bounded (doesn't create unbounded threads)
4. The pool is properly shut down when the backend is closed

### Implementation:
`backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisBackend.kt`

Replace the `parallelStream()` usage in `parallelMapFlat` (line 880-887) with a dedicated `ForkJoinPool` that is:
- Created with parallelism = `limits.maxConcurrentRequests` (default 4)
- Named with "kast-parallel-" thread prefix via a custom `ForkJoinWorkerThreadFactory`
- Stored as a field on `StandaloneAnalysisBackend` and shut down in a `close()` method (or make the backend implement `AutoCloseable`)

```kotlin
private val parallelPool = ForkJoinPool(
    limits.maxConcurrentRequests,
    { pool -> ForkJoinWorkerThread(pool).also { it.name = "kast-parallel-${it.poolIndex}" } },
    null,
    false,
)

private inline fun <T, R> List<T>.parallelMapFlat(crossinline transform: (T) -> List<R>): List<R> =
    if (size <= 1) flatMap(transform)
    else parallelPool.submit(Callable {
        parallelStream().flatMap { transform(it).stream() }.collect(Collectors.toList())
    }).get()
```

---

## Change 5: Incremental implementations() Using Declaration Index

### Test file (create):
`backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/IncrementalImplementationsTest.kt`

Tag with `@Tag("performance")`. Write tests that assert:
1. When Phase 2 declaration index is ready, `implementations()` does NOT call `session.allKtFiles()`
2. Results are correct (finds all transitive subtypes)
3. Performance: completes in less than 2s even with 500 source files when only 5 are relevant types
4. Falls back to the existing allKtFiles approach when declaration index is not ready

### Implementation:
`backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneAnalysisBackend.kt`

In the `implementations()` method (line 259-309), add a fast path that uses the SQLite `declarations` table when `session.isReferenceIndexReady()`:

1. Query `store.declarationsWithSupertype(targetFqName)` to find direct subtypes
2. Transitively expand using the declarations table (no PSI needed for the graph traversal)
3. Only load KtFiles for the final result set (to build `Symbol` models)
4. Fall back to the existing `allKtFiles()` approach if the declaration index isn't ready

This requires adding a method to `SqliteSourceIndexStore`:

```kotlin
fun declarationsWithSupertype(supertypeFqName: String): List<DeclarationRow>
```

And updating the `declarations` table schema to include a `supertypes` column (or a separate `declaration_supertypes` junction table) populated during Phase 2 scanning.

### Also update:
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt` — add `declarationsWithSupertype` query
- `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/analysis/PsiReferenceScanner.kt` — ensure `scanFileDeclarations` populates supertype info in `DeclarationRow`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/api/reference/DeclarationRow.kt` — add `supertypes: List<String>` field if not present

---

## Change 6: Dynamic Request Timeout Scaling

### Test file (create):
`analysis-server/src/test/kotlin/io/github/amichne/kast/server/DynamicTimeoutScalingTest.kt`

Write tests that assert:
1. `AnalysisServerConfig.effectiveRequestTimeoutMillis` returns the configured value for small workspaces (< 1000 files)
2. For workspaces with > 10k files, the effective timeout is scaled up (e.g., `baseTimeout * log2(fileCount / 1000)`)
3. The timeout is capped at a maximum (e.g., 5 minutes)
4. The `AnalysisDispatcher` uses `effectiveRequestTimeoutMillis` instead of raw `requestTimeoutMillis`

### Implementation:
`analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServerConfig.kt`

Add a `workspaceFileCount: Int = 0` field and a computed `effectiveRequestTimeoutMillis`:
```kotlin
val effectiveRequestTimeoutMillis: Long get() {
    if (workspaceFileCount <= 1_000) return requestTimeoutMillis
    val scaleFactor = (ln(workspaceFileCount.toDouble() / 1_000.0) / ln(2.0)).coerceAtLeast(1.0)
    return (requestTimeoutMillis * scaleFactor).toLong().coerceAtMost(300_000L)
}
```

Update `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt` (line 88) to use `config.effectiveRequestTimeoutMillis` instead of `config.requestTimeoutMillis`.

Update the standalone backend startup to pass the workspace file count to the server config after Phase 1 indexing completes.

---

## Execution Order

1. **Change 1** (Parallelize ReferenceIndexer) — standalone module, no dependencies on other changes
2. **Change 4** (Dedicated thread pool) — standalone change in backend
3. **Change 3** (Per-file timeout) — standalone change in backend
4. **Change 6** (Dynamic timeout) — standalone change in server
5. **Change 2** (Decouple Phase 2 lock) — most complex, touches session lock semantics
6. **Change 5** (Incremental implementations) — requires schema changes, most invasive

## Test Execution

Run the new tests with:
```bash
./gradlew :index-store:test --tests "*ParallelReferenceIndexer*"
./gradlew :backend-standalone:test -PincludeTags=concurrency
./gradlew :backend-standalone:test -PincludeTags=performance
./gradlew :analysis-server:test --tests "*DynamicTimeout*"
```

All tests should FAIL before implementation and PASS after. Existing tests in `ConcurrencyInvariantTest`, `SessionLockTest`, `PerformanceBaselineTest`, and `ReferenceIndexerTest` must continue to pass (regression safety).
