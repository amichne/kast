package io.github.amichne.kast.cli.mcp

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class McpChangeObservationTest {
    @Test
    fun `subprocess phase evidence retains failures without swallowing or replaying effects`() {
        for (failure in listOf(null, CancellationException("cancelled"), IllegalStateException("failed"))) {
            val events = mutableListOf<McpChangeObservation>()
            var calls = 0
            var now = 0L
            val effect = {
                observeMcpChange(McpChangePhase.APPLY, { now }, events::add) {
                    calls++
                    now = 10L
                    if (failure != null) throw failure
                    42
                }
            }
            if (failure == null) assertEquals(42, effect())
            else assertSame(failure, assertThrows<RuntimeException> { effect() })
            assertEquals(1, calls)
            assertEquals(McpChangeOutcome.STARTED, events.first().outcome)
            assertEquals(10L, events.last().elapsedNanos)
            assertEquals(
                when (failure) {
                    null -> McpChangeOutcome.RETURNED
                    is CancellationException -> McpChangeOutcome.CANCELLED
                    else -> McpChangeOutcome.FAILED
                },
                events.last().outcome,
            )
        }
    }
}
