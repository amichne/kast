package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.indexing.ReferenceIndexer
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ParallelReferenceIndexerTest {
    @TempDir
    lateinit var workspaceRoot: Path

    /**
     * With parallelism > 1, multiple files in the same batch should be scanned concurrently.
     * We verify this by tracking the maximum number of simultaneous in-flight scans using
     * an AtomicInteger counter and a latch that holds all tasks open until after all have started.
     */
    @Test
    fun `parallelism greater than 1 processes files concurrently within a batch`() {
        val fileCount = 8
        val filePaths = (0 until fileCount).map { i -> "/src/File$i.kt" }
        storeWithManifest(*filePaths.toTypedArray()).use { store ->
            val concurrentScans = AtomicInteger(0)
            val maxConcurrentScans = AtomicInteger(0)
            // Gate that keeps every task alive until we have measured concurrent depth
            val releaseLatch = CountDownLatch(1)

            ReferenceIndexer(store, batchSize = fileCount, parallelism = 4).indexReferences(
                filePaths = filePaths,
                referenceScanner = { path ->
                    val current = concurrentScans.incrementAndGet()
                    maxConcurrentScans.updateAndGet { existing -> maxOf(existing, current) }
                    // Hold this task open so other tasks get to run concurrently
                    releaseLatch.await(500, TimeUnit.MILLISECONDS)
                    concurrentScans.decrementAndGet()
                    listOf(
                        SymbolReferenceRow(
                            sourcePath = path,
                            sourceOffset = 0,
                            targetFqName = "sample.Target",
                            targetPath = null,
                            targetOffset = null,
                        ),
                    )
                },
            )
            releaseLatch.countDown()

            assertTrue(
                maxConcurrentScans.get() > 1,
                "Expected >1 concurrent scans with parallelism=4 and fileCount=$fileCount, " +
                "but max concurrent was ${maxConcurrentScans.get()}",
            )
        }
    }

    /**
     * Results must be identical whether parallelism=1 (sequential) or parallelism=4 (parallel).
     * The set of indexed references should be the same in both cases.
     */
    @Test
    fun `parallel indexing produces identical results to sequential indexing`() {
        val filePaths = (0 until 10).map { i -> "/src/File$i.kt" }

        val resultsSerial = storeWithManifest(*filePaths.toTypedArray()).use { store ->
            ReferenceIndexer(store, batchSize = 10).indexReferences(
                filePaths = filePaths,
                referenceScanner = { path ->
                    listOf(
                        SymbolReferenceRow(
                            sourcePath = path,
                            sourceOffset = 0,
                            targetFqName = "sample.Target",
                            targetPath = null,
                            targetOffset = null,
                        ),
                    )
                },
            )
            store.referencesToSymbol("sample.Target").map { it.sourcePath }.sorted()
        }

        val resultsParallel = storeWithManifest(*filePaths.toTypedArray()).use { store ->
            ReferenceIndexer(store, batchSize = 10, parallelism = 4).indexReferences(
                filePaths = filePaths,
                referenceScanner = { path ->
                    listOf(
                        SymbolReferenceRow(
                            sourcePath = path,
                            sourceOffset = 0,
                            targetFqName = "sample.Target",
                            targetPath = null,
                            targetOffset = null,
                        ),
                    )
                },
            )
            store.referencesToSymbol("sample.Target").map { it.sourcePath }.sorted()
        }

        assertEquals(resultsSerial, resultsParallel)
    }

    /**
     * When cancellation is requested before writing a batch, the database must remain empty
     * even when parallel workers have already completed their scans. Verifies that the
     * post-scan isCancelled guard works correctly in the parallel code path.
     */
    @Test
    fun `cancellation before write prevents database update in parallel mode`() {
        val fileCount = 12
        val filePaths = (0 until fileCount).map { i -> "/src/File$i.kt" }
        val scannedCount = AtomicInteger(0)
        val cancelAfter = 4

        storeWithManifest(*filePaths.toTypedArray()).use { store ->
            ReferenceIndexer(store, batchSize = fileCount, parallelism = 4).indexReferences(
                filePaths = filePaths,
                referenceScanner = { path ->
                    scannedCount.incrementAndGet()
                    listOf(
                        SymbolReferenceRow(
                            sourcePath = path,
                            sourceOffset = 0,
                            targetFqName = "sample.Target",
                            targetPath = null,
                            targetOffset = null,
                        ),
                    )
                },
                isCancelled = { scannedCount.get() >= cancelAfter },
            )

            val refsAfterCancel = store.referencesToSymbol("sample.Target")
            assertTrue(
                refsAfterCancel.isEmpty(),
                "Expected no references written after cancellation, but found ${refsAfterCancel.size}",
            )
        }
    }

    /**
     * A scanner exception on one file must not corrupt the results from other files
     * running in parallel. The failed file should be silently skipped and all other
     * files' references should be correctly indexed.
     */
    @Test
    fun `scanner exception on one file does not corrupt results from other parallel files`() {
        val filePaths = (0 until 8).map { i -> "/src/File$i.kt" }
        val failingPath = filePaths[3]

        storeWithManifest(*filePaths.toTypedArray()).use { store ->
            ReferenceIndexer(store, batchSize = filePaths.size, parallelism = 4).indexReferences(
                filePaths = filePaths,
                referenceScanner = { path ->
                    if (path == failingPath) {
                        throw RuntimeException("Simulated parallel scanner failure")
                    }
                    listOf(
                        SymbolReferenceRow(
                            sourcePath = path,
                            sourceOffset = 0,
                            targetFqName = "sample.Target",
                            targetPath = null,
                            targetOffset = null,
                        ),
                    )
                },
            )

            val refs = store.referencesToSymbol("sample.Target")
            assertEquals(
                filePaths.size - 1,
                refs.size,
                "Expected ${filePaths.size - 1} refs (all except failing file), got ${refs.size}",
            )
            assertTrue(
                refs.none { it.sourcePath == failingPath },
                "Failing file $failingPath should not appear in results",
            )
        }
    }

    private fun storeWithManifest(vararg filePaths: String): SqliteSourceIndexStore {
        val store = SqliteSourceIndexStore(workspaceRoot.toAbsolutePath().normalize())
        store.ensureSchema()
        store.saveManifest(filePaths.associateWith { 1L })
        return store
    }
}
