package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.diagnostics.*

import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.AnalysisTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Path
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

    @Test
    fun `index state is reflected in the shared backend UI state`() {
        val state = KastDiagnosticsState(
            now = { Instant.parse("2026-06-17T12:00:00Z") },
        )
        state.recordBackendStarted(AnalysisTransport.Tcp("127.0.0.1", 4123))

        state.recordIndexWaitingForIde()
        assertEquals(KastBackendUiState.INDEXING, state.snapshot().backendState)

        state.recordIndexFailed(IllegalStateException("Gradle import failed"))
        assertEquals(KastBackendUiState.DEGRADED, state.snapshot().backendState)
    }

    @Test
    fun `diagnostic delivery coalesces concurrent UI work to the latest snapshot`() {
        val parentDisposable = com.intellij.openapi.util.Disposer.newDisposable()
        val project = com.intellij.mock.MockProject(null, parentDisposable)
        val scheduled = mutableListOf<() -> Unit>()
        val delivered = mutableListOf<KastDiagnosticsSnapshot>()

        try {
            val diagnostics = KastDiagnosticsService(project) { task -> scheduled += task }
            diagnostics.addListener(parentDisposable) { snapshot -> delivered += snapshot }
            delivered.clear()

            diagnostics.recordBackendStarting(Path.of("/workspace"))
            diagnostics.recordBackendStarted(AnalysisTransport.Tcp("127.0.0.1", 4123))
            diagnostics.recordIndexWaitingForIde()

            assertEquals(1, scheduled.size)
            scheduled.single().invoke()
            assertEquals(listOf(KastBackendUiState.INDEXING), delivered.map(KastDiagnosticsSnapshot::backendState))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(parentDisposable)
        }
    }

    @Test
    fun `diagnostic delivery retries after the UI scheduler rejects once`() {
        val parentDisposable = com.intellij.openapi.util.Disposer.newDisposable()
        val project = com.intellij.mock.MockProject(null, parentDisposable)
        val scheduled = mutableListOf<() -> Unit>()
        val delivered = mutableListOf<KastDiagnosticsSnapshot>()
        var rejectNext = true

        try {
            val diagnostics = KastDiagnosticsService(project) { task ->
                if (rejectNext) {
                    rejectNext = false
                    error("UI scheduler is disposing")
                }
                scheduled += task
            }
            diagnostics.addListener(parentDisposable) { snapshot -> delivered += snapshot }
            delivered.clear()

            assertDoesNotThrow {
                diagnostics.recordBackendStarting(Path.of("/workspace"))
            }
            diagnostics.recordBackendStarted(AnalysisTransport.Tcp("127.0.0.1", 4123))

            assertEquals(1, scheduled.size)
            scheduled.single().invoke()
            assertEquals("tcp:127.0.0.1:4123", delivered.single().transport)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(parentDisposable)
        }
    }
}
