package io.github.amichne.kast.indexstore.indexing

import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_REFERENCE_BATCH_SIZE = 50

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

    fun indexReferences(
        filePaths: Collection<String>,
        referenceScanner: (String) -> List<SymbolReferenceRow>,
        declarationScanner: ((String) -> List<DeclarationRow>)? = null,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) {
        for (batch in filePaths.toList().chunked(batchSize)) {
            if (isCancelled()) break
            val referenceResults = scanBatch(batch, referenceScanner, isCancelled)
            if (isCancelled()) break

            val declarationResults = declarationScanner?.let { scanner ->
                scanBatch(batch, scanner, isCancelled)
            }
            if (isCancelled()) break

            store.replaceReferencesFromFiles(referenceResults)
            if (declarationResults != null) {
                store.replaceDeclarationsFromFiles(declarationResults)
            }
        }
    }

    fun reindexFiles(
        changedPaths: Set<String>,
        referenceScanner: (String) -> List<SymbolReferenceRow>,
        declarationScanner: ((String) -> List<DeclarationRow>)? = null,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) {
        indexReferences(
            filePaths = changedPaths,
            referenceScanner = referenceScanner,
            declarationScanner = declarationScanner,
            isCancelled = isCancelled,
        )
    }

    // -------------------------------------------------------------------------
    // Scanning helpers
    // -------------------------------------------------------------------------

    /**
     * Scans [batch] with [scanner], using either a sequential or parallel strategy
     * depending on [parallelism].
     */
    private fun <T> scanBatch(
        batch: List<String>,
        scanner: (String) -> T,
        isCancelled: () -> Boolean,
    ): List<Pair<String, T>> =
        if (parallelism > 1) {
            scanBatchParallel(batch, scanner, isCancelled)
        } else {
            batch.mapNotNull { filePath ->
                if (isCancelled()) return@mapNotNull null
                try {
                    filePath to scanner(filePath)
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
    private fun <T> scanBatchParallel(
        batch: List<String>,
        scanner: (String) -> T,
        isCancelled: () -> Boolean,
    ): List<Pair<String, T>> {
        val threadCounter = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "kast-ref-indexer-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
        return try {
            val futures = batch.map { filePath ->
                executor.submit(
                    Callable<Pair<String, T>?> {
                        if (isCancelled()) return@Callable null
                        try {
                            filePath to scanner(filePath)
                        } catch (error: Exception) {
                            if (error.isCancellation()) throw error
                            null
                        }
                    },
                )
            }
            futures.mapNotNull { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: return@mapNotNull null
                    if (cause.isCancellation()) throw cause
                    null
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedException("Interrupted while awaiting parallel scan result")
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun Throwable.isCancellation(): Boolean =
        this is CancellationException ||
        this is InterruptedException ||
        javaClass.name == "com.intellij.openapi.progress.ProcessCanceledException"
}
