package io.github.amichne.kast.runtime.hosted

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostedVfsRefreshTest {
    @Test
    fun `read admission waits for refresh callback before reporting ready`() = runTest {
        var complete: (() -> Unit)? = null
        val pending = async { awaitVfsRefresh({ false }) { callback -> complete = callback } }
        runCurrent()
        assertNull(pending.getCompletedOrNull())
        complete!!()
        assertEquals(HostedVfsRefreshOutcome.READY, pending.await())
    }

    @Test
    fun `uncompleted refresh fails closed at its bounded deadline`() = runTest {
        val pending = async { awaitVfsRefresh({ false }) {} }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(HostedVfsRefreshOutcome.DEADLINE_EXCEEDED, pending.await())
    }

    @Test
    fun `refresh failures retain finite transport causes`() = runTest {
        assertEquals(
            HostedVfsRefreshOutcome.FAILED,
            awaitVfsRefresh({ false }) { throw IllegalStateException("native refresh failed") },
        )
        assertEquals(null, HostedVfsRefreshOutcome.READY.failure())
        assertEquals(HostedEndpointFailure.IO_UNAVAILABLE, HostedVfsRefreshOutcome.ROOT_UNAVAILABLE.failure())
        assertEquals(HostedEndpointFailure.UNSAVED_DOCUMENTS, HostedVfsRefreshOutcome.UNSAVED_DOCUMENTS.failure())
        assertEquals(HostedEndpointFailure.DEADLINE_EXCEEDED, HostedVfsRefreshOutcome.DEADLINE_EXCEEDED.failure())
        assertEquals(HostedEndpointFailure.PLATFORM_UNAVAILABLE, HostedVfsRefreshOutcome.FAILED.failure())
    }

    @Test
    fun `disposed project does not start refresh and cannot become ready after callback`() = runTest {
        var disposed = true
        var started = false
        assertEquals(
            HostedVfsRefreshOutcome.PROJECT_DISPOSED,
            awaitVfsRefresh({ disposed }) { started = true },
        )
        assertEquals(false, started)
        disposed = false
        var complete: (() -> Unit)? = null
        val pending = async { awaitVfsRefresh({ disposed }) { callback -> complete = callback } }
        runCurrent()
        disposed = true
        complete!!()
        assertEquals(HostedVfsRefreshOutcome.PROJECT_DISPOSED, pending.await())
    }

    private fun <T> kotlinx.coroutines.Deferred<T>.getCompletedOrNull(): T? = if (isCompleted) getCompleted() else null
}
