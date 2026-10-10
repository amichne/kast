package io.github.amichne.kast.runtime.hosted.workspace

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class WorkspaceRefreshIncrementalRejoinTest {
    @Test
    fun `cancelled sole read waiter rejoins retained incremental attempt before native settlement`() {
        for (capacity in listOf(1, 4)) verifyRejoin(capacity, expired = false)
    }

    @Test
    fun `expired sole read waiter rejoins retained incremental attempt after history eviction`() {
        for (capacity in listOf(1, 4)) verifyRejoin(capacity, expired = true)
    }

    private fun verifyRejoin(capacity: Int, expired: Boolean) {
        val port = WorkspaceRefreshTestPort()
        var now = 0L
        val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10, capacity = capacity)
        val first = mutableListOf<WorkspaceRefreshStatus>()
        val next = mutableListOf<WorkspaceRefreshStatus>()
        val cancel = service.refreshForRead(first::add)
        val originalCallback = port.callbacks.first()
        val original = service.inspection() as WorkspaceRefreshInspection.Running
        if (expired) now = 10 else cancel()
        service.refreshForRead(next::add)
        val joined = service.inspection() as WorkspaceRefreshInspection.Running
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        originalCallback(WorkspaceRefreshEffectResult.SUCCEEDED)
        val expectedFailure =
            if (expired) WorkspaceRefreshFailure.DEADLINE_EXCEEDED else WorkspaceRefreshFailure.CANCELLED
        assertAll(
            { assertEquals(listOf(WorkspaceRefreshStatus.Failed(expectedFailure)), first) },
            { assertEquals(original.active.id, joined.active.id) },
            { assertEquals(emptyList<WorkspaceRefreshAttemptInspection>(), joined.queued) },
            { assertEquals(1, port.incrementalEffects) },
            { assertEquals(listOf(WorkspaceRefreshStatus.Complete), next) },
            { assertFalse(service.hasWork()) },
        )
    }

    @Test
    fun `actual incremental settlement permits a fresh attempt and stale callback cannot release it`() {
        for (result in listOf(WorkspaceRefreshEffectResult.SUCCEEDED, WorkspaceRefreshEffectResult.FAILED)) {
            val port = WorkspaceRefreshTestPort()
            val service = WorkspaceRefreshService(port, { 0L })
            service.refreshForRead {}
            val originalCallback = port.callbacks.first()
            port.finish(result)
            val next = mutableListOf<WorkspaceRefreshStatus>()
            service.refreshForRead(next::add)
            assertEquals(2, port.incrementalEffects)
            originalCallback(WorkspaceRefreshEffectResult.SUCCEEDED)
            assertEquals(emptyList<WorkspaceRefreshStatus>(), next)
            port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
            assertEquals(listOf(WorkspaceRefreshStatus.Complete), next)
            assertFalse(service.hasWork())
        }
    }

    @Test
    fun `bounded incremental prefixes preserve shared native work and release current waiters`() {
        forEachBoundedPrefix(listOf("read", "cancel", "expire", "settle", "stale"), 4) { prefix ->
            val case = IncrementalPrefixCase()
            prefix.forEach { event ->
                case.accept(event)
                case.assertInvariant(prefix)
            }
        }
    }
}

/** Expected starts and waiter outcomes derive from demand and observed callbacks, independently of owner entries. */
private class IncrementalPrefixCase {
    private val port = WorkspaceRefreshTestPort()
    private var now = 0L
    private val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10, capacity = 4)
    private val cancellations = mutableListOf<() -> Unit>()
    private val started = mutableListOf<Long>()
    private val expected = mutableListOf<WorkspaceRefreshStatus?>()
    private val actual = mutableListOf<MutableList<WorkspaceRefreshStatus>>()
    private val settledCallbacks = mutableListOf<(WorkspaceRefreshEffectResult) -> Unit>()
    private var active = false
    private var starts = 0

    fun accept(event: String) {
        when (event) {
            "read" -> read()
            "cancel" -> cancel()
            "expire" -> {
                now += 10
                expected.indices
                    .filter { expected[it] == null && now - started[it] >= 10 }
                    .forEach {
                        expected[it] = WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED)
                    }
            }
            "settle" -> settle()
            "stale" -> settledCallbacks.lastOrNull()?.invoke(WorkspaceRefreshEffectResult.SUCCEEDED)
        }
        service.inspection()
    }

    private fun read() {
        if (!active) {
            starts++
            active = true
        }
        expected += null
        started += now
        val outcomes = mutableListOf<WorkspaceRefreshStatus>()
        actual += outcomes
        cancellations += service.refreshForRead(outcomes::add)
    }

    private fun cancel() {
        val index = expected.lastIndex
        if (index < 0) return
        if (expected[index] == null) expected[index] = WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.CANCELLED)
        cancellations[index]()
    }

    private fun settle() {
        if (!active) return
        val callback = port.callbacks.removeFirst()
        settledCallbacks += callback
        active = false
        expected.indices.filter { expected[it] == null }.forEach { expected[it] = WorkspaceRefreshStatus.Complete }
        callback(WorkspaceRefreshEffectResult.SUCCEEDED)
    }

    fun assertInvariant(prefix: List<String>) {
        assertEquals(starts, port.incrementalEffects, prefix.toString())
        expected.indices.forEach { index ->
            assertEquals(listOfNotNull(expected[index]), actual[index], "$prefix waiter=$index")
        }
        assertEquals(active, service.hasWork(), prefix.toString())
    }
}
