package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationOmissionSample
import io.github.amichne.kast.relation.contract.RelationOmissionSampleRetention
import io.github.amichne.kast.relation.contract.RelationProviderKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Native recording adapter rules only; these detached observations make no compiler or platform claim. */
class RelationOmissionObservationTest {
    @Test
    fun `a fourth distinct location records sample truncation without erasing the observed item count`() {
        val file = RelationReadTest().request(RelationMeaning.References).subject.file
        val locations =
            (0 until 4).map { ordinal ->
                (RelationOccurrence.fromBoundary(file, ordinal * 2, ordinal * 2 + 1) as Refinement.Refined).value
            }
        val observation = IntellijRelationOmissionObservation(RelationProviderKind.INTELLIJ_REFERENCES_V2)
        locations.take(3).forEach { location ->
            repeat(2) {
                observation.record(RelationLimitation.UNSUPPORTED_ITEM, RelationOmissionSample.Located(location))
            }
        }
        val complete = observation.summarize(setOf(RelationLimitation.UNSUPPORTED_ITEM)).refined().single()
        assertEquals(RelationOmissionSampleRetention.COMPLETE, complete.samples.retention)
        assertEquals(locations.take(3), complete.samples.locations)
        observation.record(RelationLimitation.UNSUPPORTED_ITEM, RelationOmissionSample.Located(locations.last()))
        val truncated = observation.summarize(setOf(RelationLimitation.UNSUPPORTED_ITEM)).refined().single()
        assertEquals(RelationOmissionSampleRetention.TRUNCATED, truncated.samples.retention)
        assertEquals(locations.take(3), truncated.samples.locations)
        assertEquals(7L, (truncated.measurement as RelationOmissionMeasurement.ObservedOnPage).items.value)
        assertEquals(RelationOmissionSampleRetention.COMPLETE, complete.samples.retention)
        assertThrows(UnsupportedOperationException::class.java) {
            (truncated.samples.locations as MutableList<RelationOccurrence>).clear()
        }
    }

    @Test
    fun `unlocated omissions preserve observed counts without manufacturing located sample truncation`() {
        val observation = IntellijRelationOmissionObservation(RelationProviderKind.INTELLIJ_REFERENCES_V2)
        repeat(4) { observation.record(RelationLimitation.PROVIDER_INCOMPLETE, RelationOmissionSample.Unavailable) }
        val evidence = observation.summarize(setOf(RelationLimitation.PROVIDER_INCOMPLETE)).refined().single()
        assertEquals(RelationOmissionSampleRetention.COMPLETE, evidence.samples.retention)
        assertEquals(emptyList<RelationOccurrence>(), evidence.samples.locations)
        assertEquals(4L, (evidence.measurement as RelationOmissionMeasurement.ObservedOnPage).items.value)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected native recording proof, got $failure")
        }
}
