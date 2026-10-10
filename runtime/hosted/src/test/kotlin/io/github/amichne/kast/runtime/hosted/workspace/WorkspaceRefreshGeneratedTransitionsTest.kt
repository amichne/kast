package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceRefreshGeneratedTransitionsTest {
    @Test
    fun `generated bounded prefixes preserve one native attempt and reject stale completions`() {
        val alphabet = listOf("demand", "read", "cancel", "timeout", "settle", "stale", "dispose")
        forEachBoundedPrefix(alphabet, 4) { prefix ->
            val case = RefreshPrefixCase()
            prefix.forEach { event ->
                case.accept(event)
                case.assertInvariant(prefix)
            }
        }
    }
}

/** Independent effect oracle observes starts, actual callbacks, and retirement, never owner state as expected state. */
private class RefreshPrefixCase {
    private val port = WorkspaceRefreshTestPort().apply { ready = true }
    private var now = 0L
    private val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10, capacity = 2)
    private var request = 0
    private var settled = 0
    private var retired = false
    private var startsAtRetirement = 0
    private val cancellations = mutableListOf<() -> Unit>()
    private val completed = mutableListOf<(WorkspaceRefreshEffectResult) -> Unit>()

    fun accept(event: String) {
        when (event) {
            "demand" -> service.submit(id(++request), WorkspaceRefreshEffect.FILE_REFRESH, stamp())
            "read" -> cancellations += service.refreshForRead {}
            "cancel" -> cancellations.lastOrNull()?.invoke()
            "timeout" -> timeout()
            "settle" -> settle()
            "stale" -> completed.lastOrNull()?.invoke(WorkspaceRefreshEffectResult.SUCCEEDED)
            "dispose" -> retire()
        }
    }

    private fun timeout() {
        now += 10
        if (request > 0) service.status(id(request))
    }

    private fun settle() {
        if (port.callbacks.isEmpty()) return
        val callback = port.callbacks.removeFirst()
        completed += callback
        settled++
        callback(WorkspaceRefreshEffectResult.FAILED)
    }

    private fun retire() {
        service.dispose()
        retired = true
        startsAtRetirement = port.effects.size
    }

    fun assertInvariant(prefix: List<String>) {
        assertEquals(true, port.effects.size <= settled + 1, prefix.toString())
        if (port.callbacks.isNotEmpty() && !retired) assertEquals(true, service.hasWork(), prefix.toString())
        if (retired) assertEquals(startsAtRetirement, port.effects.size, prefix.toString())
    }

    private fun id(value: Int) = (WorkspaceRefreshRequestId.parse("request-$value") as Refinement.Refined).value

    private fun stamp() = (WorkspaceRefreshStamp.parse(1) as Refinement.Refined).value
}
