package io.github.amichne.kast.indexstore

import java.util.concurrent.CancellationException

private const val DEFAULT_REFERENCE_BATCH_SIZE = 50

/**
 * Batch engine for rebuilding `symbol_references`.
 *
 * Scanning runs outside SQLite transactions; each batch is then written in a
 * short transaction so slow PSI resolution never holds the database write lock.
 */
class ReferenceIndexer(
    private val store: SqliteSourceIndexStore,
    private val batchSize: Int = DEFAULT_REFERENCE_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "Reference index batch size must be positive" }
    }

    fun indexReferences(
        filePaths: Collection<String>,
        referenceScanner: (String) -> List<SymbolReferenceRow>,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) {
        for (batch in filePaths.toList().chunked(batchSize)) {
            if (isCancelled()) break
            val batchResults = batch.mapNotNull { filePath ->
                if (isCancelled()) return@mapNotNull null
                try {
                    filePath to referenceScanner(filePath)
                } catch (error: Exception) {
                    if (error.isCancellation()) throw error
                    null
                }
            }
            if (isCancelled()) break

            store.replaceReferencesFromFiles(batchResults)
        }
    }

    fun reindexFiles(
        changedPaths: Set<String>,
        referenceScanner: (String) -> List<SymbolReferenceRow>,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) {
        indexReferences(
            filePaths = changedPaths,
            referenceScanner = referenceScanner,
            isCancelled = isCancelled,
        )
    }

    private fun Throwable.isCancellation(): Boolean =
        this is CancellationException ||
            this is InterruptedException ||
            javaClass.name == "com.intellij.openapi.progress.ProcessCanceledException"
}
