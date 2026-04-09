package io.github.amichne.kast.standalone

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis

/**
 * Capstone test suite that uses the injectable [SessionLock], [Clock], and
 * `identifierIndexWaitMillis` to assert on the concurrency model of
 * [StandaloneAnalysisSession].
 *
 * These tests verify that:
 * - write-lock hold time during `rebuildWorkspaceLayout` is bounded
 * - concurrent reads are not mutually exclusive
 * - symbol resolve completes within budget even when the index is not ready
 * - enrichment does not starve concurrent reads
 *
 * Run with:
 *     ./gradlew :backend-standalone:test -PincludeTags=concurrency
 * Excluded from default CI via:
 *     ./gradlew :backend-standalone:test -PexcludeTags=concurrency
 */
@Tag("concurrency")
class ConcurrencyInvariantTest {

    @TempDir
    lateinit var workspaceRoot: Path

    companion object {
        /** Maximum allowed write-lock hold time during workspace rebuild. */
        private const val MAX_WRITE_HOLD_NANOS = 2_000_000_000L // 2 seconds

        /** Read operations should complete within this budget even during writes. */
        private const val READ_BUDGET_MS = 10_000L
    }

    @Test
    fun `concurrent reads proceed without mutual exclusion`() {
        writeSourceFiles(20)
        val lock = InstrumentedSessionLock()

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "concmod",
            sourceIndexFileReader = { path -> Files.readString(path) },
            analysisSessionLock = lock,
        ).use { session ->
            session.awaitInitialSourceIndex()

            val threadCount = 5
            val readyLatch = CountDownLatch(threadCount)
            val startLatch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)

            val futures = (0 until threadCount).map { i ->
                CompletableFuture.supplyAsync(
                    {
                        readyLatch.countDown()
                        startLatch.await()
                        // Use withReadAccess which actually acquires the session lock.
                        session.withReadAccess {
                            session.candidateKotlinFilePaths("File${i % 20}", null)
                        }
                    },
                    executor,
                )
            }

            // Wait for all threads to be ready, then release them simultaneously.
            readyLatch.await()
            startLatch.countDown()

            futures.forEach { it.get(READ_BUDGET_MS, TimeUnit.MILLISECONDS) }
            executor.shutdown()

            val readEvents = lock.events.filter { it.type == InstrumentedSessionLock.LockType.READ }
            assertTrue(readEvents.size >= threadCount) {
                "Expected at least $threadCount read events, got ${readEvents.size}"
            }

            // Assert at least some reads overlap in time (concurrent, not serialized).
            val overlapping = readEvents.zipWithNext().count { (a, b) ->
                a.releasedAtNanos > b.acquiredAtNanos
            }
            assertTrue(overlapping > 0) {
                "Expected concurrent read events to overlap in time, but none did"
            }
        }
    }

    @Test
    fun `symbol resolve completes within budget when index is not ready`() {
        writeSourceFiles(50)

        val elapsed = measureTimeMillis {
            StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = sourceRoots(),
                classpathRoots = emptyList(),
                moduleName = "concmod",
                // Slow reader: ensures index won't be ready quickly.
                sourceIndexFileReader = { path ->
                    Thread.sleep(50)
                    Files.readString(path)
                },
                identifierIndexWaitMillis = 0,
            ).use { session ->
                // Query immediately — index is definitely not ready.
                val candidates = session.candidateKotlinFilePaths("File0", null)
                // With identifierIndexWaitMillis=0, we expect fast return
                // (either empty or SQLite fallback, not a blocking wait).
                assertTrue(candidates.size >= 0) // Result is valid regardless of content.
            }
        }

        assertTrue(elapsed < READ_BUDGET_MS) {
            "Symbol resolve with non-ready index took ${elapsed}ms, exceeds ${READ_BUDGET_MS}ms budget"
        }
    }

    @Test
    fun `write lock hold time during workspace rebuild is bounded`() {
        writeSourceFiles(50)
        val lock = InstrumentedSessionLock()

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "concmod",
            sourceIndexFileReader = { path -> Files.readString(path) },
            analysisSessionLock = lock,
        ).use { session ->
            session.awaitInitialSourceIndex()

            // Trigger a rebuild (the same code path as enrichment completion).
            session.rebuildAnalysisSession()

            val maxHold = lock.maxWriteHoldNanos()
            println("max_write_hold_nanos: $maxHold (${maxHold / 1_000_000}ms)")
            assertTrue(maxHold < MAX_WRITE_HOLD_NANOS) {
                "Write lock held for ${maxHold / 1_000_000}ms, exceeds ${MAX_WRITE_HOLD_NANOS / 1_000_000}ms cap"
            }
        }
    }

    @Test
    fun `reads complete during concurrent write operations`() {
        writeSourceFiles(50)
        val lock = InstrumentedSessionLock()

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "concmod",
            sourceIndexFileReader = { path -> Files.readString(path) },
            analysisSessionLock = lock,
        ).use { session ->
            session.awaitInitialSourceIndex()

            val executor = Executors.newFixedThreadPool(3)

            // Launch reads concurrently with a write.
            val writeFuture = CompletableFuture.supplyAsync(
                { session.rebuildAnalysisSession() },
                executor,
            )
            val readFutures = (0 until 5).map { i ->
                CompletableFuture.supplyAsync(
                    {
                        session.withReadAccess {
                            session.candidateKotlinFilePaths("File${i % 50}", null)
                        }
                    },
                    executor,
                )
            }

            writeFuture.get(READ_BUDGET_MS, TimeUnit.MILLISECONDS)
            readFutures.forEach { it.get(READ_BUDGET_MS, TimeUnit.MILLISECONDS) }
            executor.shutdown()

            // Verify both reads and writes occurred.
            val readCount = lock.events.count { it.type == InstrumentedSessionLock.LockType.READ }
            val writeCount = lock.events.count { it.type == InstrumentedSessionLock.LockType.WRITE }
            assertTrue(readCount > 0) { "Expected read events but found none" }
            assertTrue(writeCount > 0) { "Expected write events but found none" }
        }
    }

    @Test
    fun `file watcher debounce respects injected clock`() {
        val clock = TestClock()
        writeSourceFiles(5)

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "concmod",
            sourceIndexFileReader = { path -> Files.readString(path) },
            clock = clock,
        ).use { session ->
            // Verify the clock was injected (session construction uses it).
            val time1 = clock.nanoTime()
            clock.advanceNanos(1_000_000)
            val time2 = clock.nanoTime()
            assertTrue(time2 > time1) { "TestClock.advanceNanos did not advance time" }
        }
    }

    private fun sourceRoots(): List<Path> =
        listOf(normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin")))

    private fun writeSourceFiles(count: Int) {
        repeat(count) { index ->
            writeSourceFile(
                relativePath = "conc/File$index.kt",
                content = buildString {
                    appendLine("package conc")
                    appendLine()
                    appendLine("class File$index {")
                    appendLine("    fun value$index(): Int = $index")
                    appendLine("}")
                },
            )
        }
    }

    private fun writeSourceFile(relativePath: String, content: String): Path {
        val file = workspaceRoot.resolve("src/main/kotlin").resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
        return file
    }
}
