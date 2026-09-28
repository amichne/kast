package io.github.amichne.kast.change.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IntellijWritePhaseObservationTest {
    @Test
    fun `commit and save evidence measures the effect without manufacturing success`() {
        for (phase in IntellijWritePhase.entries) {
            var now = 10L
            val observations = mutableListOf<IntellijWritePhaseObservation>()
            val returned =
                observeIntellijWritePhase(phase, { now }, observations::add) {
                    now = 25L
                    IntellijSessionStepResult.Rejected(
                        io.github.amichne.kast.change.apply.SourceWriteFailure.SAVE_FAILED
                    )
                }
            assertEquals(IntellijWritePhaseOutcome.RETURNED, observations.last().outcome)
            assertEquals(15L, observations.last().elapsedNanos)
            assertEquals(IntellijWritePhaseOutcome.STARTED, observations.first().outcome)
            assertEquals(io.github.amichne.kast.change.apply.SourceWriteFailure.SAVE_FAILED, returned.failure)
        }
    }

    @Test
    fun `platform cancellation and failure propagate with a terminal phase`() {
        for (failure in listOf(ProcessCanceledException(), IllegalStateException("fixture"))) {
            val observations = mutableListOf<IntellijWritePhaseObservation>()
            val thrown =
                assertThrows<RuntimeException> {
                    observeIntellijWritePhase(IntellijWritePhase.EXPLICIT_SAVE, { 0L }, observations::add) {
                        throw failure
                    }
                }
            assertSame(failure, thrown)
            assertEquals(
                if (failure is ProcessCanceledException) IntellijWritePhaseOutcome.CANCELLED
                else IntellijWritePhaseOutcome.FAILED,
                observations.last().outcome,
            )
        }
    }
}
