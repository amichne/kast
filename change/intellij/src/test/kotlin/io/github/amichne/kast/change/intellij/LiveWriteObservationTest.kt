package io.github.amichne.kast.change.intellij

import io.github.amichne.kast.change.apply.AppliedSourceWriteFailure
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class LiveWriteObservationTest {
    @Test
    fun `successful refinement emits bounded evidence and preserves its proof`() {
        val proof = Refinement.Refined("source payload must never be logged")
        val events = mutableListOf<LiveWriteObservation>()
        assertSame(proof, observeLiveWriteResult(proof, events::add))
        assertEquals(listOf(LiveWriteObservation.Applied), events)
        assertEquals("kast_live_write_observation {\"type\":\"applied\"}", events.single().encode())
    }

    @Test
    fun `every rejected refinement retains its exact finite cause`() {
        for (failure in AppliedSourceWriteFailure.entries) {
            val proof = Refinement.Rejected(failure)
            val events = mutableListOf<LiveWriteObservation>()
            assertSame(proof, observeLiveWriteResult(proof, events::add))
            assertEquals(listOf(LiveWriteObservation.Rejected(failure)), events)
            assertEquals(
                "kast_live_write_observation {\"type\":\"rejected\",\"failure\":\"$failure\"}",
                events.single().encode(),
            )
        }
    }
}
