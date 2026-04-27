package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.ReferenceIndexer
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.SymbolReferenceRow
import io.github.amichne.kast.standalone.cache.SourceIndexCache
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Manages eager background indexing in two phases:
 *
 * - **Phase 1 (identifier index)**: A fast text-only scan that builds
 *   [MutableSourceIdentifierIndex] from source files. This runs immediately on
 *   [startPhase1] and completes [identifierIndexReady].
 *
 * - **Phase 2 (symbol references)**: A deeper scan that resolves K2 symbol
 *   references and populates the `symbol_references` table in SQLite. Triggered
 *   via [startPhase2] after Phase 1 and the K2 session are ready. Completes
 *   [referenceIndexReady].
 *
 * The indexer is designed to be cancelled cleanly: [close] interrupts in-flight
 * work and completes both futures so callers never hang.
 */
internal class BackgroundIndexer(
    private val sourceRoots: List<Path>,
    private val sourceIndexFileReader: (Path) -> String,
    private val sourceModuleNameResolver: (NormalizedPath) -> ModuleName?,
    private val sourceIndexCache: SourceIndexCache,
    private val store: SqliteSourceIndexStore,
    private val initialSourceIndexBuilder: (() -> Map<String, List<String>>)? = null,
) : AutoCloseable {

    val identifierIndexReady = CompletableFuture<Unit>()
    val referenceIndexReady = CompletableFuture<Unit>()

    private val generation = AtomicInteger(0)
    private val indexRef = AtomicReference<MutableSourceIdentifierIndex?>(null)
    private val phase1HeadCommit = AtomicReference<String?>(null)

    @Volatile
    private var cancelled = false
    private var phase1Thread: Thread? = null
    private var phase2Thread: Thread? = null

    /**
     * Starts Phase 1 (identifier index) on a daemon thread. Returns the
     * generation counter so the caller can detect stale results.
     */
    /**
     * Starts Phase 1 (identifier index) on a daemon thread.
     *
     * @param onIndexBuilt called synchronously in the Phase 1 thread, with the completed
     *   index, **before** [identifierIndexReady] is completed. This guarantees that any
     *   state the caller publishes via this callback is visible to all threads that wait
     *   on [identifierIndexReady].
     */
    fun startPhase1(onIndexBuilt: ((MutableSourceIdentifierIndex) -> Unit)? = null): Int {
        val gen = generation.incrementAndGet()
        phase1Thread = thread(
            start = true,
            isDaemon = true,
            name = "kast-background-indexer-phase1",
        ) {
            runCatching {
                if (cancelled) return@thread
                initialSourceIndexBuilder
                    ?.invoke()
                    ?.let(MutableSourceIdentifierIndex::fromCandidatePathsByIdentifier)
                    ?: loadOrBuildIndex()
            }.onSuccess { index ->
                if (cancelled || generation.get() != gen) return@onSuccess
                indexRef.set(index)
                runCatching {
                    sourceIndexCache.save(
                        index = index,
                        sourceRoots = sourceRoots,
                        headCommit = phase1HeadCommit.get()
                    )
                }
                // Publish the index synchronously before completing the future, so any
                // waiter on identifierIndexReady is guaranteed to see the updated state.
                onIndexBuilt?.invoke(index)
                identifierIndexReady.complete(Unit)
            }.onFailure { error ->
                if (cancelled || generation.get() != gen) return@onFailure
                identifierIndexReady.completeExceptionally(error)
            }
        }
        return gen
    }

    /**
     * Starts Phase 2 (symbol reference index) on a daemon thread. The
     * [referenceScanner] callback resolves references for a single file path
     * and returns a list of [SymbolReferenceRow]s. It is called inside the
     * caller-provided read-access context (e.g., K2 analysis session).
     */
    fun startPhase2(
        changedPaths: Set<String>? = null,
        referenceScanner: (String) -> List<SymbolReferenceRow>,
    ) {
        phase2Thread = thread(
            start = true,
            isDaemon = true,
            name = "kast-background-indexer-phase2",
        ) {
            runCatching {
                if (cancelled) return@thread
                val allPaths = changedPaths ?: store.loadManifest()?.keys ?: return@thread
                generation.incrementAndGet()
                ReferenceIndexer(store, batchSize = PHASE2_BATCH_SIZE).indexReferences(
                    filePaths = allPaths,
                    referenceScanner = referenceScanner,
                    isCancelled = { cancelled || Thread.currentThread().isInterrupted },
                )
                if (!cancelled) {
                    referenceIndexReady.complete(Unit)
                }
            }.onFailure { error ->
                if (cancelled) return@onFailure
                if (!referenceIndexReady.isDone) {
                    referenceIndexReady.completeExceptionally(error)
                }
            }
        }
    }

    /** Returns the current identifier index, or null if Phase 1 hasn't completed. */
    fun getIndex(): MutableSourceIdentifierIndex? = indexRef.get()

    /** Returns the current generation counter. */
    fun currentGeneration(): Int = generation.get()

    /**
     * Re-indexes a set of changed file paths incrementally. Skips files that
     * no longer exist on disk (deleted between discovery and read).
     *
     * When [referenceScanner] is provided, also triggers an incremental Phase 2
     * re-scan for the same changed paths after the Phase 1 update completes.
     */
    fun reindexFiles(
        index: MutableSourceIdentifierIndex,
        paths: Set<NormalizedPath>,
        referenceScanner: ((String) -> List<SymbolReferenceRow>)? = null,
    ) {
        paths.forEach { normalizedPath ->
            val filePath = normalizedPath.toJavaPath()
            if (!java.nio.file.Files.isRegularFile(filePath)) {
                index.removeFile(normalizedPath.value)
                sourceIndexCache.saveRemovedFile(normalizedPath.value)
                return@forEach
            }
            runCatching {
                index.updateFile(
                    normalizedPath = normalizedPath.value,
                    newContent = sourceIndexFileReader(filePath),
                    moduleName = sourceModuleNameResolver(normalizedPath),
                )
                sourceIndexCache.saveFileIndex(index, normalizedPath)
            }
        }
        if (referenceScanner != null) {
            val changedPathStrings = paths.map { it.value }.toSet()
            ReferenceIndexer(store, batchSize = PHASE2_BATCH_SIZE).reindexFiles(
                changedPaths = changedPathStrings,
                referenceScanner = referenceScanner,
                isCancelled = { cancelled || Thread.currentThread().isInterrupted },
            )
        }
    }

    override fun close() {
        cancelled = true
        phase1Thread?.interrupt()
        phase2Thread?.interrupt()
        // Wait for threads to observe cancellation before completing futures,
        // so callers (e.g. JUnit @TempDir cleanup) can safely delete source files.
        phase1Thread?.join(2000)
        phase2Thread?.join(2000)
        if (!identifierIndexReady.isDone) {
            identifierIndexReady.complete(Unit)
        }
        if (!referenceIndexReady.isDone) {
            // Complete exceptionally so callers can distinguish a clean Phase-2
            // completion from a cancellation.  isReferenceIndexReady() checks
            // isCompletedExceptionally to avoid treating cancelled futures as ready.
            referenceIndexReady.completeExceptionally(
                java.util.concurrent.CancellationException("BackgroundIndexer closed"),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1 internals
    // -------------------------------------------------------------------------

    companion object {
        /** Number of files to batch per Phase 2 transaction to reduce SQLite write contention. */
        private const val PHASE2_BATCH_SIZE = 50
    }

    private fun loadOrBuildIndex(): MutableSourceIdentifierIndex {
        val incrementalResult = runCatching {
            sourceIndexCache.load(sourceRoots)
        }.getOrNull()
        val index = incrementalResult?.index ?: return buildFullIndex().also { phase1HeadCommit.set(null) }
        phase1HeadCommit.set(incrementalResult.headCommit)
        incrementalResult.deletedPaths.forEach(index::removeFile)
        (incrementalResult.newPaths + incrementalResult.modifiedPaths).forEach { pathString ->
            if (cancelled || Thread.currentThread().isInterrupted) return index
            refreshFileIndex(index, NormalizedPath.ofNormalized(pathString))
        }
        return index
    }

    private fun buildFullIndex(): MutableSourceIdentifierIndex {
        val index = MutableSourceIdentifierIndex(
            pathsByIdentifier = java.util.concurrent.ConcurrentHashMap(),
            identifiersByPath = java.util.concurrent.ConcurrentHashMap(),
        )
        allTrackedKotlinSourcePaths().forEach { normalizedFilePath ->
            if (cancelled || Thread.currentThread().isInterrupted) return index
            val normalizedPath = NormalizedPath.ofNormalized(normalizedFilePath)
            runCatching {
                index.updateFile(
                    normalizedPath = normalizedFilePath,
                    newContent = sourceIndexFileReader(normalizedPath.toJavaPath()),
                    moduleName = sourceModuleNameResolver(normalizedPath),
                )
            }
            // Skip files that fail to read (e.g., deleted between discovery and read)
        }
        return index
    }

    private fun refreshFileIndex(
        index: MutableSourceIdentifierIndex,
        normalizedPath: NormalizedPath,
    ) {
        val filePath = normalizedPath.toJavaPath()
        if (!java.nio.file.Files.isRegularFile(filePath)) {
            index.removeFile(normalizedPath.value)
            return
        }
        runCatching {
            index.updateFile(
                normalizedPath = normalizedPath.value,
                newContent = sourceIndexFileReader(filePath),
                moduleName = sourceModuleNameResolver(normalizedPath),
            )
        }
    }

    private fun allTrackedKotlinSourcePaths(): Set<String> =
        io.github.amichne.kast.standalone.cache.scanTrackedKotlinFileTimestamps(sourceRoots).keys
}
