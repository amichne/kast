package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RelationOmissionSamplesTest {
    @Test
    fun `four distinct observed locations retain exactly three and prove truncation`() {
        val locations = listOf(10, 20, 30, 40).map(::location)
        val complete = RelationOmissionSamples.observed(locations.take(3))
        assertEquals(RelationOmissionSampleRetention.COMPLETE, complete.retention)
        assertSame(complete, complete.observe(locations.first()))
        val truncated = complete.observe(locations.last())
        assertEquals(RelationOmissionSampleRetention.TRUNCATED, truncated.retention)
        assertEquals(locations.take(3), truncated.locations)
        assertSame(truncated, truncated.observe(location(50)))
        assertSame(truncated, truncated.observe(locations.last()))
        assertEquals(complete, RelationOmissionSamples.observed(locations.take(3)))
        assertThrows(UnsupportedOperationException::class.java) {
            (truncated.locations as MutableList<RelationOccurrence>).clear()
        }
    }

    @Test
    fun `unlocated omitted items do not manufacture located sample truncation`() {
        val evidence =
            RelationOmissionEvidence.fromObservedPage(
                    RelationProviderKind.INTELLIJ_REFERENCES_V2,
                    RelationLimitation.UNSUPPORTED_ITEM,
                    RelationOmissionMeasurement.ObservedOnPage(RelationWorkCount.parse(40).refined()),
                    RelationOmissionSamples.Empty,
                )
                .refined()
        assertEquals(emptyList<RelationOccurrence>(), evidence.samples.locations)
        assertEquals(RelationOmissionSampleRetention.COMPLETE, evidence.samples.retention)
    }

    @Test
    fun `located observations cannot exceed their measured provider items`() {
        val samples = RelationOmissionSamples.observed(listOf(10, 20, 30, 40).map(::location))
        val rejected =
            RelationOmissionEvidence.fromObservedPage(
                RelationProviderKind.INTELLIJ_REFERENCES_V2,
                RelationLimitation.UNSUPPORTED_ITEM,
                RelationOmissionMeasurement.ObservedOnPage(RelationWorkCount.parse(3).refined()),
                samples,
            )
        assertEquals(
            RelationOmissionEvidenceFailure.INCONSISTENT_SAMPLE_MEASUREMENT,
            (rejected as Refinement.Rejected).failure,
        )
        val unmeasured =
            RelationOmissionEvidence.fromObservedPage(
                    RelationProviderKind.INTELLIJ_REFERENCES_V2,
                    RelationLimitation.UNSUPPORTED_ITEM,
                    RelationOmissionMeasurement.UnmeasuredOnPage,
                    samples,
                )
                .refined()
        assertSame(samples, unmeasured.samples)
        assertEquals(RelationOmissionMeasurement.UnmeasuredOnPage, unmeasured.measurement)
    }

    private fun location(start: Int): RelationOccurrence {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/src/Catalog.kt")).refined()
            )
        return RelationOccurrence.fromBoundary(file, start, start + 1).refined()
    }

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
