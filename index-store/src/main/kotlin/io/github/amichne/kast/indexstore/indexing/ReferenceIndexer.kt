package io.github.amichne.kast.indexstore.indexing

import io.github.amichne.kast.api.client.fields.RelationshipIndexingBatchSize
import io.github.amichne.kast.api.client.fields.RelationshipIndexingParallelism
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.FileStageFailureUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_REFERENCE_BATCH_SIZE = 50

sealed interface RelationshipScanResult {
    val contentHash: FileContentHash

    data class Indexed(
        override val contentHash: FileContentHash,
        val references: List<SymbolReferenceRow>,
        val declarations: List<DeclarationRow>,
        val limitations: List<FileStageLimitation> = emptyList(),
    ) : RelationshipScanResult

    data class Failed(
        override val contentHash: FileContentHash,
        val code: FileStageFailureCode,
        val message: String,
    ) : RelationshipScanResult

    companion object {
        operator fun invoke(
            contentHash: FileContentHash,
            references: List<SymbolReferenceRow>,
            declarations: List<DeclarationRow>,
            limitations: List<FileStageLimitation> = emptyList(),
        ): RelationshipScanResult = Indexed(contentHash, references, declarations, limitations)
    }
}

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
    private val batchSize: RelationshipIndexingBatchSize =
        RelationshipIndexingBatchSize(DEFAULT_REFERENCE_BATCH_SIZE),
    private val parallelism: RelationshipIndexingParallelism = RelationshipIndexingParallelism(1),
) {
    fun indexSymbolRelationships(
        filePaths: Collection<String>,
        referenceScanner: (String) -> List<SymbolReferenceRow>,
        declarationScanner: ((String) -> List<DeclarationRow>)? = null,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onFilesIndexed: (Collection<String>) -> Unit = {},
    ) {
        val executor = createExecutor()
        try {
            for (batch in filePaths.asSequence().chunked(batchSize.value)) {
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
        scanner: (WorkspaceSourcePath) -> RelationshipScanResult,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onFilesIndexed: (List<WorkspaceSourcePath>) -> Unit = {},
    ) {
        val executor = createExecutor()
        try {
            for (batch in work.asSequence().chunked(batchSize.value)) {
                if (isCancelled()) break
                val scanned = scanBatch(batch, { pending -> scanner(pending.path) }, isCancelled, executor)
                if (isCancelled()) break
                val updates = scanned.mapNotNull { (pending, result) ->
                    if (result !is RelationshipScanResult.Indexed ||
                        result.contentHash != pending.contentHash
                    ) {
                        return@mapNotNull null
                    }
                    RelationshipFileStageUpdate(
                        work = pending,
                        scannedContentHash = result.contentHash,
                        references = result.references,
                        declarations = result.declarations,
                        limitations = result.limitations,
                    )
                }
                val failures = scanned.mapNotNull { (pending, result) ->
                    if (result !is RelationshipScanResult.Failed ||
                        result.contentHash != pending.contentHash
                    ) {
                        return@mapNotNull null
                    }
                    FileStageFailureUpdate(
                        work = pending,
                        scannedContentHash = result.contentHash,
                        code = result.code,
                        message = result.message,
                    )
                }
                if (updates.isEmpty() && failures.isEmpty()) continue
                store.commitRelationshipBatch(updates, failures)
                if (isCancelled()) break
                onFilesIndexed(updates.map { update -> update.work.path })
            }
        } finally {
            executor?.shutdownNow()
        }
    }

    /** Retries durable PSI limitations without making them ordinary pending work first. */
    fun retryLimitedSymbolRelationships(
        scanner: (WorkspaceSourcePath) -> RelationshipScanResult,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onFilesIndexed: (List<WorkspaceSourcePath>) -> Unit = {},
    ) {
        indexPendingSymbolRelationships(
            work = store.retryableLimitedRelationshipStages(),
            scanner = scanner,
            isCancelled = isCancelled,
            onFilesIndexed = onFilesIndexed,
        )
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
                input to scanner(input)
            }
        }

    /**
     * Scans [batch] files concurrently using a fixed thread pool of size [parallelism].
     * Thread names are prefixed with `"kast-ref-indexer-"`.
     *
     * Scanner failures are rethrown to the caller. Expected file-local failures
     * must be returned as [RelationshipScanResult.Failed].
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
                    input to scanner(input)
                },
            )
        }
        return futures.mapNotNull { future ->
            try {
                future.get()
            } catch (e: ExecutionException) {
                throw checkNotNull(e.cause) { "Parallel scan failed without a cause" }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedException("Interrupted while awaiting parallel scan result")
            }
        }
    }

    private fun createExecutor(): ExecutorService? {
        if (parallelism.value == 1) return null
        val threadCounter = AtomicInteger(0)
        return Executors.newFixedThreadPool(parallelism.value) { runnable ->
            Thread(runnable, "kast-ref-indexer-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }
}
