package io.github.amichne.kast.runtime.hosted

import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HostedChangeObservationTest {
    @Test
    fun `permit is released after cancellation and after observation failure`() =
        kotlinx.coroutines.test.runTest {
            val mutex = kotlinx.coroutines.sync.Mutex()
            org.junit.jupiter.api.assertThrows<kotlinx.coroutines.TimeoutCancellationException> {
                kotlinx.coroutines.withTimeout(1) {
                    withHostedMutationPermit(mutex, publish = {}) { kotlinx.coroutines.awaitCancellation() }
                }
            }
            assertEquals(false, mutex.isLocked)
            assertThrows<IllegalStateException> {
                withHostedMutationPermit(
                    mutex,
                    publish = {
                        if (it.outcome == HostedChangePhaseOutcome.RETURNED) error("observation failed")
                    },
                ) {
                    error("must not apply")
                }
            }
            assertEquals(false, mutex.isLocked)
            assertEquals(42, withHostedMutationPermit(mutex, publish = {}) { 42 })
        }

    @Test
    fun `timeout while waiting never releases another operation permit`() =
        kotlinx.coroutines.test.runTest {
            val mutex = kotlinx.coroutines.sync.Mutex()
            val existingOwner = Any()
            mutex.lock(existingOwner)
            val observations = mutableListOf<HostedChangePhaseObservation>()
            org.junit.jupiter.api.assertThrows<kotlinx.coroutines.TimeoutCancellationException> {
                kotlinx.coroutines.withTimeout(1) {
                    withHostedMutationPermit(mutex, publish = observations::add) { error("must not apply") }
                }
            }
            assertEquals(true, mutex.holdsLock(existingOwner))
            assertEquals(HostedChangePhaseOutcome.DEADLINE_EXCEEDED, observations.last().outcome)
            mutex.unlock(existingOwner)
            assertEquals(42, withHostedMutationPermit(mutex, publish = {}) { 42 })
        }

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
            assertEquals(
                listOf(
                    HostedChangePhaseOutcome.STARTED,
                    when (failure) {
                        null -> HostedChangePhaseOutcome.RETURNED
                        is CancellationException -> HostedChangePhaseOutcome.CANCELLED
                        else -> HostedChangePhaseOutcome.FAILED
                    },
                ),
                observations.map { it.outcome },
            )
            assertEquals(10.nanoseconds, observations.last().elapsed)
            assertEquals(null, observations.last().plan)
        }
    }
}
