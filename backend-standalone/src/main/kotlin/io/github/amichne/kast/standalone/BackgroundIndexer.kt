package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ModuleName
import io.github.amichne.kast.api.NormalizedPath
import io.github.amichne.kast.standalone.cache.SourceIndexCache
import io.github.amichne.kast.standalone.cache.SqliteSourceIndexStore
import io.github.amichne.kast.standalone.cache.SymbolReferenceRow
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

    @Volatile
    private var cancelled = false
    private var phase1Thread: Thread? = null
    private var phase2Thread: Thread? = null

    /**
     * Starts Phase 1 (identifier index) on a daemon thread. Returns the
     * generation counter so the caller can detect stale results.
     */
    fun startPhase1(): Int {
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
                runCatching { sourceIndexCache.save(index = index, sourceRoots = sourceRoots) }
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
    fun startPhase2(referenceScanner: (String) -> List<SymbolReferenceRow>) {
        phase2Thread = thread(
            start = true,
            isDaemon = true,
            name = "kast-background-indexer-phase2",
        ) {
            runCatching {
                if (cancelled) return@thread
                val allPaths = store.loadManifest()?.keys ?: return@thread
                generation.incrementAndGet()
                for (filePath in allPaths) {
                    if (cancelled || Thread.currentThread().isInterrupted) break
                    runCatching {
                        store.clearReferencesFromFile(filePath)
                        val refs = referenceScanner(filePath)
                        refs.forEach { ref ->
                            store.upsertSymbolReference(
                                sourcePath = ref.sourcePath,
                                sourceOffset = ref.sourceOffset,
                                targetFqName = ref.targetFqName,
                                targetPath = ref.targetPath,
                                targetOffset = ref.targetOffset,
                            )
                        }
                    }
                }
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
     */
    fun reindexFiles(
        index: MutableSourceIdentifierIndex,
        paths: Set<NormalizedPath>,
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
    }

    override fun close() {
        cancelled = true
        phase1Thread?.interrupt()
        phase2Thread?.interrupt()
        if (!identifierIndexReady.isDone) {
            identifierIndexReady.complete(Unit)
        }
        if (!referenceIndexReady.isDone) {
            referenceIndexReady.complete(Unit)
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1 internals
    // -------------------------------------------------------------------------

    private fun loadOrBuildIndex(): MutableSourceIdentifierIndex {
        val incrementalResult = runCatching {
            sourceIndexCache.load(sourceRoots)
        }.getOrNull()
        val index = incrementalResult?.index ?: return buildFullIndex()
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
