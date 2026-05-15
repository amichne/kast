package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ServerLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Field
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

/**
 * Verifies that [StandaloneAnalysisBackend] uses a dedicated [ForkJoinPool] for
 * parallel file scanning (not the common ForkJoinPool), that the pool is bounded by
 * [ServerLimits.maxConcurrentRequests], and that it is properly shut down via [AutoCloseable].
 *
 * All tests are tagged [Tag("concurrency")] and can be run with:
 *   ./gradlew :backend-standalone:test -PincludeTags=concurrency
 *
 * DESIGN: The tests access `parallelPool` via reflection because it is `private`. A field
 * named `parallelPool` must exist on [StandaloneAnalysisBackend] — tests will fail
 * with a descriptive message when the field is absent (which is the case on unimplemented code).
 */
@Tag("concurrency")
class DedicatedParallelPoolTest {

    @TempDir
    lateinit var workspaceRoot: Path

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeBackend(maxConcurrentRequests: Int = 4): StandaloneAnalysisBackend =
        StandaloneAnalysisBackend(
            workspaceRoot = workspaceRoot,
            limits = ServerLimits(
                maxResults = 100,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = maxConcurrentRequests,
            ),
            session = StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = emptyList(),
                classpathRoots = emptyList(),
                moduleName = "pool-test",
            ),
        )

    /**
     * Retrieves the private `parallelPool` field from a [StandaloneAnalysisBackend] via
     * reflection, or returns `null` if the field does not exist.
     */
    private fun getParallelPoolOrNull(backend: StandaloneAnalysisBackend): ForkJoinPool? {
        val field: Field = try {
            backend.javaClass.getDeclaredField("parallelPool")
        } catch (_: NoSuchFieldException) {
            return null
        }
        field.isAccessible = true
        return field.get(backend) as? ForkJoinPool
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [StandaloneAnalysisBackend] must implement [AutoCloseable] so callers can use
     * try-with-resources / Kotlin `use {}` to guarantee the dedicated pool is shut down.
     *
     * FAILS on unimplemented code: the class currently does not implement [AutoCloseable].
     */
    @Test
    fun `StandaloneAnalysisBackend implements AutoCloseable`() {
        val backend = makeBackend()
        assertTrue(
            backend is AutoCloseable,
            "StandaloneAnalysisBackend must implement AutoCloseable so that the dedicated " +
            "ForkJoinPool can be shut down via close(). " +
            "Current interfaces: " + backend.javaClass.interfaces.toList(),
        )
    }

    /**
     * A private field named `parallelPool` of type [ForkJoinPool] must be present on the
     * [StandaloneAnalysisBackend] class.
     *
     * FAILS on unimplemented code: the field does not exist yet.
     */
    @Test
    fun `backend has a private parallelPool ForkJoinPool field`() {
        val backend = makeBackend()
        val pool = getParallelPoolOrNull(backend)
        assertNotNull(
            pool,
            "Expected a private field 'parallelPool: ForkJoinPool' on StandaloneAnalysisBackend " +
            "but it was not found. Has the dedicated pool been added?",
        )
    }

    /**
     * Worker threads inside `parallelPool` must be named with the prefix `kast-parallel-`
     * so they are identifiable in JVM thread dumps and heap profiles.
     *
     * FAILS on unimplemented code: the field does not exist; null-safe chain returns null
     * and the assertion fails via error().
     */
    @Test
    fun `parallelPool worker threads are named with kast-parallel- prefix`() {
        val backend = makeBackend()
        val pool = getParallelPoolOrNull(backend)
                   ?: error(
                       "parallelPool field not found on StandaloneAnalysisBackend. " +
                       "Add a private val parallelPool: ForkJoinPool before this test can pass.",
                   )

        // Submit a trivial task that captures the executing thread's name.
        val threadName = pool.submit(Callable { Thread.currentThread().name })
            .get(10, TimeUnit.SECONDS)

        assertTrue(
            threadName.startsWith("kast-parallel-"),
            "Expected parallelPool worker threads to have names starting with " +
            "'kast-parallel-' but got: '$threadName'. " +
            "Is the ForkJoinWorkerThreadFactory setting thread names correctly?",
        )
    }

    /**
     * [ForkJoinPool.parallelism] must equal [ServerLimits.maxConcurrentRequests] so that the
     * pool is bounded and cannot monopolise available CPU cores beyond what the server limit
     * allows.
     *
     * FAILS on unimplemented code: pool field absent.
     */
    @Test
    fun `parallelPool parallelism is bounded by maxConcurrentRequests`() {
        val maxConcurrentRequests = 3
        val backend = makeBackend(maxConcurrentRequests = maxConcurrentRequests)
        val pool = getParallelPoolOrNull(backend)
                   ?: error("parallelPool field not found. Cannot verify parallelism bound.")

        assertEquals(
            maxConcurrentRequests,
            pool.parallelism,
            "ForkJoinPool.parallelism should equal ServerLimits.maxConcurrentRequests " +
            "(=$maxConcurrentRequests), but got ${pool.parallelism}.",
        )
    }

    /**
     * Calling [AutoCloseable.close] on the backend must shut down the dedicated pool so
     * that no worker threads are leaked after the backend is no longer needed.
     *
     * FAILS on unimplemented code: class does not implement [AutoCloseable].
     */
    @Test
    fun `close() shuts down the parallelPool`() {
        val backend = makeBackend()

        assertTrue(
            backend is AutoCloseable,
            "StandaloneAnalysisBackend must implement AutoCloseable before close() can be tested.",
        )
        (backend as AutoCloseable).close()

        val pool = getParallelPoolOrNull(backend)
                   ?: error("parallelPool field not found. Cannot verify pool shutdown.")

        assertTrue(
            pool.isShutdown,
            "Expected parallelPool to be shut down after backend.close() was called, " +
            "but pool.isShutdown == false. Is close() calling parallelPool.shutdown()?",
        )
    }

    /**
     * `parallelPool` must NOT be the JVM global [ForkJoinPool.commonPool]. Using the
     * common pool for long-running work can starve other library code that relies on it.
     *
     * FAILS on unimplemented code: pool field absent.
     */
    @Test
    fun `parallelPool is not the ForkJoinPool common pool`() {
        val backend = makeBackend()
        val pool = getParallelPoolOrNull(backend)
                   ?: error("parallelPool field not found.")

        assertTrue(
            pool !== ForkJoinPool.commonPool(),
            "parallelPool must be a dedicated ForkJoinPool instance, not ForkJoinPool.commonPool().",
        )
    }
}
