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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis

/**
 * Regression tests for Phase 2 / foreground read starvation.
 *
 * Root cause: [StandaloneReferenceIndexEnvironment.withExclusiveAccess] calls
 * `session.withExclusiveAccess` which acquires a PENDING write lock in Java's fair
 * [java.util.concurrent.locks.ReentrantReadWriteLock]. Once a write acquisition is
 * pending, the fair policy prevents **new** read requests from proceeding — even though
 * existing readers are still executing concurrently.
 *
 * Fix: Replace the indefinite `withExclusiveAccess` call with a `tryWrite(timeoutMillis)`
 * retry loop. When the write acquisition times out, Phase 2 removes itself from the waiter
 * queue, allowing queued reads to proceed before Phase 2 retries.
 *
 * Run with:
 *     ./gradlew :backend-standalone:test -PincludeTags=concurrency
 * Excluded from default CI via:
 *     ./gradlew :backend-standalone:test -PexcludeTags=concurrency
 */
@Tag("concurrency")
class Phase2ReadStarvationTest {

    @TempDir
    lateinit var workspaceRoot: Path

    /**
     * Verifies that a new foreground read is NOT starved while Phase 2's write-lock
     * acquisition is pending (waiting for an active read to complete).
     *
     * Failure mode with current code: Phase 2 calls `session.withExclusiveAccess` which
     * blocks indefinitely as a pending write waiter. Java's fair lock then prevents ANY
     * subsequent read from acquiring the read lock, so a new foreground read submitted
     * after Phase 2 becomes a waiter is blocked for >> 500 ms.
     */
    @Test
    fun `foreground reads are not starved by pending Phase 2 exclusive access`() {
        writeSourceFile()
        val lock = InstrumentedSessionLock()

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "starvation-test",
            sourceIndexFileReader = { path -> Files.readString(path) },
            analysisSessionLock = lock,
            enablePhase2Indexing = false,
        ).use { session ->
            val environment = StandaloneReferenceIndexEnvironment(
                session = session,
                store = session.sqliteStore,
                cancelled = { false },
            )
            val executor = Executors.newFixedThreadPool(3)
            val readHeld = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)

            // Thread 1: hold READ lock indefinitely, simulating an ongoing foreground operation
            val readHolderFuture = CompletableFuture.runAsync({
                session.withReadAccess {
                    readHeld.countDown()
                    releaseRead.await(10, TimeUnit.SECONDS)
                }
            }, executor)

            assertTrue(readHeld.await(10, TimeUnit.SECONDS), "Reader did not acquire the lock")

            // Thread 2: Phase 2 attempts exclusive access while Thread 1 holds the read lock.
            // With current code it enqueues as a PENDING WRITE WAITER and blocks indefinitely.
            val phase2Future = CompletableFuture.runAsync({
                environment.withExclusiveAccess { Unit }
            }, executor)

            // Allow enough time for Phase 2 to become the pending write waiter
            Thread.sleep(200)

            // Thread 3: new foreground read.
            // With current code: blocked by Thread 2's pending write waiter -> ~2000 ms -> FAILS.
            // With new code (tryWrite 300 ms timeout): Thread 2 dequeues at ~300 ms ->
            //   Thread 3 acquires read lock concurrently with Thread 1 -> ~200 ms -> PASSES.
            val readLatencyMs = measureTimeMillis {
                val newReadFuture = CompletableFuture.runAsync(
                    { session.withReadAccess { Unit } },
                    executor,
                )
                try {
                    newReadFuture.get(2_000, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // read was starved; latency recorded as ~2000 ms
                }
            }

            assertTrue(
                readLatencyMs < 500L,
                "Foreground read took ${readLatencyMs}ms while Phase 2 was a pending write waiter. " +
                    "Phase 2 must not starve new foreground reads (expected < 500ms).",
            )

            releaseRead.countDown()
            executor.shutdownNow()
            runCatching { readHolderFuture.get(5, TimeUnit.SECONDS) }
            runCatching { phase2Future.get(5, TimeUnit.SECONDS) }
        }
    }

    /**
     * Verifies that foreground reads complete within 500 ms even when a slow Phase 2 scanner
     * (simulating 100 ms/file work) is pending on the write lock.
     *
     * This mirrors the PsiReferenceScanner pattern where each file scan holds the write lock
     * for a bounded amount of time, but *waiting* for the write lock must not spill over to
     * starve subsequent read requests.
     */
    @Test
    fun `foreground reads complete within 500ms while slow Phase 2 scan is pending`() {
        writeSourceFile()

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "starvation-slow-test",
            sourceIndexFileReader = { path -> Files.readString(path) },
            enablePhase2Indexing = false,
        ).use { session ->
            val environment = StandaloneReferenceIndexEnvironment(
                session = session,
                store = session.sqliteStore,
                cancelled = { false },
            )
            val executor = Executors.newFixedThreadPool(3)
            val readHeld = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)

            // Thread 1: hold READ lock while Phase 2 attempts to scan
            val readHolderFuture = CompletableFuture.runAsync({
                session.withReadAccess {
                    readHeld.countDown()
                    releaseRead.await(10, TimeUnit.SECONDS)
                }
            }, executor)

            assertTrue(readHeld.await(10, TimeUnit.SECONDS), "Reader did not acquire the lock")

            // Thread 2: Phase 2 with slow per-file scan (100 ms) — becomes pending write waiter
            val phase2Future = CompletableFuture.runAsync({
                environment.withExclusiveAccess { Thread.sleep(100) }
            }, executor)

            Thread.sleep(200)

            // Thread 3: new foreground read must not be blocked by Thread 2's write waiter
            val readLatencyMs = measureTimeMillis {
                val newReadFuture = CompletableFuture.runAsync(
                    { session.withReadAccess { Unit } },
                    executor,
                )
                try {
                    newReadFuture.get(2_000, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // starved
                }
            }

            assertTrue(
                readLatencyMs < 500L,
                "Foreground read took ${readLatencyMs}ms with a slow Phase 2 scan pending " +
                    "(expected < 500ms).",
            )

            releaseRead.countDown()
            executor.shutdownNow()
            runCatching { readHolderFuture.get(5, TimeUnit.SECONDS) }
            runCatching { phase2Future.get(5, TimeUnit.SECONDS) }
        }
    }

    /**
     * Verifies that Phase 2 eventually scans ALL files correctly despite continuous write-lock
     * contention from foreground reads, and that reads are not starved during the process.
     *
     * Three concurrent Phase 2 scans compete for the write lock while a foreground reader holds
     * it. A fourth foreground read submitted during the contention must complete within 500 ms,
     * and all 3 file scans must eventually complete correctly.
     */
    @Test
    fun `Phase 2 scans all files correctly despite write lock contention from foreground reads`() {
        writeSourceFiles(3)

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "starvation-correctness-test",
            sourceIndexFileReader = { path -> Files.readString(path) },
            enablePhase2Indexing = false,
        ).use { session ->
            val environment = StandaloneReferenceIndexEnvironment(
                session = session,
                store = session.sqliteStore,
                cancelled = { false },
            )
            val executor = Executors.newFixedThreadPool(5)
            val readHeld = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)

            // Thread 1: hold READ lock while Phase 2 scans are submitted
            val readHolderFuture = CompletableFuture.runAsync({
                session.withReadAccess {
                    readHeld.countDown()
                    releaseRead.await(10, TimeUnit.SECONDS)
                }
            }, executor)

            assertTrue(readHeld.await(10, TimeUnit.SECONDS), "Reader did not acquire the lock")

            // Phase 2: 3 concurrent file scans (each 50 ms), all pending write waiters
            val scanned = AtomicInteger(0)
            val phase2Futures = (0 until 3).map {
                CompletableFuture.runAsync({
                    environment.withExclusiveAccess {
                        Thread.sleep(50)
                        scanned.incrementAndGet()
                    }
                }, executor)
            }

            Thread.sleep(200)

            // New foreground read: must not be blocked by the 3 pending write waiters
            val readLatencyMs = measureTimeMillis {
                val newReadFuture = CompletableFuture.runAsync(
                    { session.withReadAccess { Unit } },
                    executor,
                )
                try {
                    newReadFuture.get(2_000, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // starved
                }
            }

            assertTrue(
                readLatencyMs < 500L,
                "Foreground read took ${readLatencyMs}ms while 3 Phase 2 scans were pending " +
                    "(expected < 500ms).",
            )

            releaseRead.countDown()
            // All 3 scans must eventually succeed (correctness invariant)
            CompletableFuture.allOf(*phase2Futures.toTypedArray()).get(10, TimeUnit.SECONDS)

            assertTrue(
                scanned.get() == 3,
                "Expected 3 files scanned, got ${scanned.get()}",
            )

            executor.shutdownNow()
            runCatching { readHolderFuture.get(5, TimeUnit.SECONDS) }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun sourceRoots() = listOf(normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin")))

    private fun writeSourceFile() = writeSourceFiles(1)

    private fun writeSourceFiles(count: Int) {
        repeat(count) { index ->
            val file = workspaceRoot.resolve("src/main/kotlin/starvation/File$index.kt")
            file.parent.createDirectories()
            file.writeText("package starvation\nclass File$index")
        }
    }
}
