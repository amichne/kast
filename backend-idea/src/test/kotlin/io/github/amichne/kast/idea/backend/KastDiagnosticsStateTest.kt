package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.diagnostics.*

import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `only terminal backend and index failures notify once`() {
        val state = KastDiagnosticsState(
            now = { Instant.parse("2026-06-17T12:00:00Z") },
        )
        val deduplicator = KastTerminalFailureDeduplicator()
        val terminal = state.recordBackendFailed(IllegalStateException("plugin stale"))
        val operation = state.recordOperationFailed(
            operation = KastBackendOperation.WORKSPACE_SEARCH,
            durationMillis = 25,
            error = IllegalStateException("query failed"),
        )

        assertTrue(terminal.isActionableTerminalFailure())
        assertTrue(deduplicator.first(terminal.title, terminal.detail.orEmpty()))
        assertFalse(deduplicator.first(terminal.title, terminal.detail.orEmpty()))
        assertFalse(operation.isActionableTerminalFailure())
    }

    @Test
    fun `runtime reaches ready only after the Kast reference index`() {
        val readyBackend = RuntimeStatusResponse(
            state = RuntimeState.READY,
            healthy = true,
            active = true,
            indexing = false,
            backendName = "idea",
            backendVersion = "test",
            workspaceRoot = "/workspace",
        )

        val indexing = readyBackend.withReferenceIndex(
            KastSourceIndexSummary(state = KastIndexState.INDEXING),
        )
        val ready = readyBackend.withReferenceIndex(
            KastSourceIndexSummary(state = KastIndexState.READY),
        )
        val degraded = readyBackend.withReferenceIndex(
            KastSourceIndexSummary(
                state = KastIndexState.FAILED,
                message = "Gradle import failed",
            ),
        )

        assertEquals(RuntimeState.INDEXING, indexing.state)
        assertFalse(indexing.referenceIndexReady)
        assertEquals(RuntimeState.READY, ready.state)
        assertTrue(ready.referenceIndexReady)
        assertEquals(RuntimeState.DEGRADED, degraded.state)
        assertFalse(degraded.healthy)
    }
}
