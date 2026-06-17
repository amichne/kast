package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class KastDiagnosticsStateTest {
    @Test
    fun `operation lifecycle updates request counts and keeps bounded history`() {
        val state = KastDiagnosticsState(
            maxEvents = 2,
            now = { Instant.parse("2026-06-17T12:00:00Z") },
        )

        state.recordOperationStarted(KastBackendOperation.RESOLVE_SYMBOL)
        state.recordOperationSucceeded(KastBackendOperation.RESOLVE_SYMBOL, durationMillis = 12)
        state.recordOperationStarted(KastBackendOperation.DIAGNOSTICS)

        val snapshot = state.snapshot()
        assertEquals(1, snapshot.activeRequests)
        assertEquals(1, snapshot.completedRequests)
        assertEquals(0, snapshot.failedRequests)
        assertEquals(2, snapshot.recentEvents.size)
        assertEquals("Diagnostics started", snapshot.recentEvents.first().title)
    }

    @Test
    fun `failed operation marks backend message and failure count`() {
        val state = KastDiagnosticsState(
            now = { Instant.parse("2026-06-17T12:00:00Z") },
        )

        state.recordOperationStarted(KastBackendOperation.WORKSPACE_SEARCH)
        state.recordOperationFailed(
            operation = KastBackendOperation.WORKSPACE_SEARCH,
            durationMillis = 25,
            error = IllegalStateException("index unavailable"),
        )

        val snapshot = state.snapshot()
        assertEquals(0, snapshot.activeRequests)
        assertEquals(1, snapshot.failedRequests)
        assertEquals("Workspace search failed", snapshot.message)
    }
}
