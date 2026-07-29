package io.github.amichne.kast.indexstore.indexing

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_REFERENCE_BATCH_SIZE = 50

data class RelationshipScanResult(
    val contentHash: FileContentHash,
    val references: List<SymbolReferenceRow>,
    val declarations: List<DeclarationRow>,
    val limitations: List<FileStageLimitation> = emptyList(),
)

/**
 * Batch engine for rebuilding `symbol_references`.
 *
 * Scanning runs outside SQLite transactions; each batch is then written in a
 * short transaction so slow PSI resolution never holds the database write lock.
 *
 * When [parallelism] > 1 a fixed thread pool with [parallelism] threads is used
 * to scan files within each batch concurrently. The SQLite write phase always
 * runs on the calling thread after all parallel scans complete.
 */
class ReferenceIndexer(
    private val store: SqliteSourceIndexStore,
    private val batchSize: Int = DEFAULT_REFERENCE_BATCH_SIZE,
    private val parallelism: Int = 1,
) {
    init {
        require(batchSize > 0) { "Reference index batch size must be positive" }
        require(parallelism > 0) { "Parallelism must be positive" }
    }

    fun indexSymbolRelationships(
        filePaths: Collection<String>,
        referenceScanner: (String) -> List<SymbolReferenceRow>,
        declarationScanner: ((String) -> List<DeclarationRow>)? = null,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onFilesIndexed: (Collection<String>) -> Unit = {},
    ) {
        val executor = createExecutor()
        try {
            for (batch in filePaths.asSequence().chunked(batchSize)) {
                if (isCancelled()) break
                val referenceResults = scanBatch(batch, referenceScanner, isCancelled, executor)
                if (isCancelled()) break

                val declarationResults = declarationScanner?.let { scanner ->
                    scanBatch(batch, scanner, isCancelled, executor)
                }
                if (isCancelled()) break

                val referencePaths = referenceResults.mapTo(mutableSetOf()) { it.first }
                val declarationPaths = declarationResults?.mapTo(mutableSetOf()) { it.first }
                val successfulPaths = batch.filter { path ->
                    path in referencePaths &&
                        (declarationPaths == null || path in declarationPaths)
                }
                store.replaceReferencesFromFiles(referenceResults.filter { it.first in successfulPaths })
                if (declarationResults != null) {
                    store.replaceDeclarationsFromFiles(declarationResults.filter { it.first in successfulPaths })
                }
                if (isCancelled()) break
                onFilesIndexed(successfulPaths)
            }
        } finally {
            executor?.shutdownNow()
        }
    }

    fun indexPendingSymbolRelationships(
        work: Collection<PendingFileStage>,
        scanner: (String) -> RelationshipScanResult,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onFilesIndexed: (List<String>) -> Unit = {},
    ) {
        val executor = createExecutor()
        try {
            for (batch in work.asSequence().chunked(batchSize)) {
                if (isCancelled()) break
                val scanned = scanBatch(batch, { pending -> scanner(pending.path) }, isCancelled, executor)
                if (isCancelled()) break
                val updates = scanned.mapNotNull { (pending, result) ->
                    if (result.contentHash != pending.contentHash) return@mapNotNull null
                    RelationshipFileStageUpdate(
                        work = pending,
                        scannedContentHash = result.contentHash,
                        references = result.references,
                        declarations = result.declarations,
                        limitations = result.limitations,
                    )
                }
                if (updates.isEmpty()) continue
                store.commitRelationshipBatch(updates)
                if (isCancelled()) break
                onFilesIndexed(updates.map { update -> update.work.path })
            }
        } finally {
            executor?.shutdownNow()
        }
    }

    fun reindexFiles(
        changedPaths: Set<String>,
        referenceScanner: (String) -> List<SymbolReferenceRow>,
        declarationScanner: ((String) -> List<DeclarationRow>)? = null,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onFilesIndexed: (Collection<String>) -> Unit = {},
    ) {
        indexSymbolRelationships(
            filePaths = changedPaths,
            referenceScanner = referenceScanner,
            declarationScanner = declarationScanner,
            isCancelled = isCancelled,
            onFilesIndexed = onFilesIndexed,
        )
    }

    // -------------------------------------------------------------------------
    // Scanning helpers
    // -------------------------------------------------------------------------

    /**
     * Scans [batch] with [scanner], using either a sequential or parallel strategy
     * depending on [parallelism].
     */
    private fun <K, T> scanBatch(
        batch: List<K>,
        scanner: (K) -> T,
        isCancelled: () -> Boolean,
        executor: ExecutorService?,
    ): List<Pair<K, T>> =
        if (executor != null) {
            scanBatchParallel(batch, scanner, isCancelled, executor)
        } else {
            batch.mapNotNull { input ->
                if (isCancelled()) return@mapNotNull null
                try {
                    input to scanner(input)
                } catch (error: Exception) {
                    if (error.isCancellation()) throw error
                    null
                }
            }
        }

    /**
     * Scans [batch] files concurrently using a fixed thread pool of size [parallelism].
     * Thread names are prefixed with `"kast-ref-indexer-"`.
     *
     * Non-cancellation exceptions from [scanner] are swallowed (file is skipped).
     * Cancellation exceptions are rethrown to the caller.
     */
    private fun <K, T> scanBatchParallel(
        batch: List<K>,
        scanner: (K) -> T,
        isCancelled: () -> Boolean,
        executor: ExecutorService,
    ): List<Pair<K, T>> {
        val futures = batch.map { input ->
            executor.submit(
                Callable<Pair<K, T>?> {
                    if (isCancelled()) return@Callable null
                    try {
                        input to scanner(input)
                    } catch (error: Exception) {
                        if (error.isCancellation()) throw error
                        null
                    }
                },
            )
        }
        return futures.mapNotNull { future ->
            try {
                future.get()
            } catch (e: ExecutionException) {
                val cause = e.cause ?: return@mapNotNull null
                if (cause.isCancellation()) throw cause
                if (cause !is Exception) throw cause
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedException("Interrupted while awaiting parallel scan result")
            }
        }
    }

    private fun createExecutor(): ExecutorService? {
        if (parallelism == 1) return null
        val threadCounter = AtomicInteger(0)
        return Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "kast-ref-indexer-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }

    private fun Throwable.isCancellation(): Boolean =
        this is CancellationException ||
            this is InterruptedException ||
            javaClass.name == "com.intellij.openapi.progress.ProcessCanceledException"
}
