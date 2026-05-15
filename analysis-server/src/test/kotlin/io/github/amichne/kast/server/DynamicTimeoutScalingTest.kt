package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.validation.ParsedApplyEditsQuery
import io.github.amichne.kast.api.validation.ParsedDiagnosticsQuery
import io.github.amichne.kast.api.validation.ParsedReferencesQuery
import io.github.amichne.kast.api.validation.ParsedRenameQuery
import io.github.amichne.kast.api.validation.ParsedSymbolQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln

/**
 * TDD tests for dynamic request timeout scaling in [AnalysisServerConfig].
 *
 * Verifies that [AnalysisServerConfig.effectiveRequestTimeoutMillis] scales the
 * raw [AnalysisServerConfig.requestTimeoutMillis] based on [AnalysisServerConfig.workspaceFileCount],
 * and that [AnalysisDispatcher] uses the effective (scaled) timeout rather than the raw one.
 */
class DynamicTimeoutScalingTest {

    @Test
    fun effectiveTimeoutReturnBaseForSmallWorkspace() {
        val config = AnalysisServerConfig(workspaceFileCount = 500)
        assertEquals(30_000L, config.effectiveRequestTimeoutMillis)
    }

    @Test
    fun effectiveTimeoutReturnsBaseForWorkspaceExactlyAtThreshold() {
        val config = AnalysisServerConfig(workspaceFileCount = 1_000)
        assertEquals(30_000L, config.effectiveRequestTimeoutMillis)
    }

    @Test
    fun effectiveTimeoutIsScaledUpForLargeWorkspace() {
        val fileCount = 10_000
        val baseTimeout = 30_000L
        val config = AnalysisServerConfig(requestTimeoutMillis = baseTimeout, workspaceFileCount = fileCount)

        val scaleFactor = (ln(fileCount.toDouble() / 1_000.0) / ln(2.0)).coerceAtLeast(1.0)
        val expected = (baseTimeout * scaleFactor).toLong()

        assertEquals(expected, config.effectiveRequestTimeoutMillis)
        assertTrue(
            config.effectiveRequestTimeoutMillis > baseTimeout,
            "Effective timeout should be greater than base for fileCount=$fileCount",
        )
    }

    @Test
    fun effectiveTimeoutIsCappedAt300Seconds() {
        val config = AnalysisServerConfig(requestTimeoutMillis = 300_000L, workspaceFileCount = 1_000_000)
        assertEquals(300_000L, config.effectiveRequestTimeoutMillis)
    }

    @Test
    fun effectiveTimeoutCapEnforcedEvenWhenScalingExceedsIt() {
        // With 100_000 files and 200_000ms base: scale ≈ log2(100) ≈ 6.64 → 1_328_000ms >> 300_000ms cap
        val config = AnalysisServerConfig(requestTimeoutMillis = 200_000L, workspaceFileCount = 100_000)

        val scaleFactor = (ln(100_000.0 / 1_000.0) / ln(2.0)).coerceAtLeast(1.0)
        val uncapped = (200_000L * scaleFactor).toLong()
        assertTrue(uncapped > 300_000L, "Setup invariant: uncapped value $uncapped should exceed cap")

        assertEquals(300_000L, config.effectiveRequestTimeoutMillis)
    }

    /**
     * Verifies that [AnalysisDispatcher] uses [AnalysisServerConfig.effectiveRequestTimeoutMillis]
     * rather than raw [AnalysisServerConfig.requestTimeoutMillis].
     *
     * With workspaceFileCount=50_000 and base=100ms:
     *   effectiveTimeout ≈ 100 * log2(50) ≈ 564ms
     *
     * The backend deliberately takes 200ms. If the dispatcher used the raw 100ms timeout the
     * request would always time out; because the dispatcher must use effectiveRequestTimeoutMillis
     * (≈564ms > 200ms) the request must complete successfully.
     */
    @Test
    fun dispatcherUsesEffectiveTimeoutAllowingSlowRequestsForLargeWorkspaces() = runBlocking {
        val config = AnalysisServerConfig(requestTimeoutMillis = 100, workspaceFileCount = 50_000)
        val dispatcher = AnalysisDispatcher(
            backend = SlowHealthBackend(delayMs = 200),
            config = config,
        )

        val request = """{"jsonrpc":"2.0","method":"health","id":"1"}"""
        val response = dispatcher.dispatchRaw(request)

        assertTrue(
            response.contains("\"result\""),
            "Expected success response but got (possible timeout): $response",
        )
    }
}

/**
 * A minimal [AnalysisBackend] that introduces an artificial delay in [health] to allow
 * timeout-sensitivity tests without depending on [FakeAnalysisBackend] setup.
 */
private class SlowHealthBackend(private val delayMs: Long) : AnalysisBackend {

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = "slow-test-backend",
        backendVersion = "0.0.0",
        workspaceRoot = "/tmp/test",
        readCapabilities = emptySet(),
        mutationCapabilities = emptySet(),
        limits = ServerLimits(
            maxResults = 100,
            requestTimeoutMillis = 30_000,
            maxConcurrentRequests = 1,
        ),
    )

    override suspend fun health() = run {
        delay(delayMs)
        super.health()
    }

    override suspend fun resolveSymbol(query: ParsedSymbolQuery) =
        error("not implemented in SlowHealthBackend")

    override suspend fun findReferences(query: ParsedReferencesQuery) =
        error("not implemented in SlowHealthBackend")

    override suspend fun diagnostics(query: ParsedDiagnosticsQuery) =
        error("not implemented in SlowHealthBackend")

    override suspend fun rename(query: ParsedRenameQuery) =
        error("not implemented in SlowHealthBackend")

    override suspend fun applyEdits(query: ParsedApplyEditsQuery) =
        error("not implemented in SlowHealthBackend")
}
