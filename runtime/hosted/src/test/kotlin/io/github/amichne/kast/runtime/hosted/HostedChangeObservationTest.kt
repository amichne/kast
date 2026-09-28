package io.github.amichne.kast.runtime.hosted

import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HostedChangeObservationTest {
    @Test
    fun `phase records bounded elapsed return and propagates cancellation and failure`() {
        for (failure in listOf(null, CancellationException("private"), IllegalStateException("private"))) {
            val observations = mutableListOf<HostedChangePhaseObservation>()
            var now = 10L
            val action = {
                observeHostedChange(HostedChangeStage.APPLICATION, clock = { now }, publish = observations::add) {
                    now = 20L
                    if (failure != null) throw failure
                    42
                }
            }
            if (failure == null) assertEquals(42, action())
            else assertSame(failure, assertThrows<RuntimeException> { action() })
            assertEquals(listOf(HostedChangePhaseOutcome.STARTED,
                when (failure) {
                    null -> HostedChangePhaseOutcome.RETURNED
                    is CancellationException -> HostedChangePhaseOutcome.CANCELLED
                    else -> HostedChangePhaseOutcome.FAILED
                }), observations.map { it.outcome })
            assertEquals(10.nanoseconds, observations.last().elapsed)
            assertEquals(null, observations.last().plan)
        }
    }
}
