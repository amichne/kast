package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationOmissionSample
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationOmissionObservationTest {
    @Test
    fun `observed page counts remain exact while sample locations stay bounded`() {
        val observed = IntellijRelationOmissionObservation(RelationProviderKind.INTELLIJ_REFERENCES_V2)
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                (CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/src/Catalog.kt"))
                        as Refinement.Refined)
                    .value
            )
        repeat(7) { offset ->
            val occurrence = (RelationOccurrence.fromBoundary(file, offset, offset + 1) as Refinement.Refined).value
            observed.record(RelationLimitation.UNRESOLVED_TARGET, RelationOmissionSample.Located(occurrence))
        }
        val records =
            observed.summarize(setOf(RelationLimitation.UNRESOLVED_TARGET, RelationLimitation.PROVIDER_INCOMPLETE))
        val omission = records.single { it.reason == RelationLimitation.UNRESOLVED_TARGET }
        assertEquals(7L, (omission.measurement as RelationOmissionMeasurement.ObservedOnPage).items.value)
        assertEquals(listOf(0, 1, 2), omission.samples.map { it.range.startInclusive })
        assertEquals(RelationProviderKind.INTELLIJ_REFERENCES_V2, omission.provider)
        assertTrue(
            records.single { it.reason == RelationLimitation.PROVIDER_INCOMPLETE }.measurement
                is RelationOmissionMeasurement.UnmeasuredOnPage
        )
        assertTrue(
            IntellijRelationOmissionObservation(RelationProviderKind.INTELLIJ_CALLEES_V2)
                .summarize(emptySet())
                .isEmpty()
        )
    }
}
