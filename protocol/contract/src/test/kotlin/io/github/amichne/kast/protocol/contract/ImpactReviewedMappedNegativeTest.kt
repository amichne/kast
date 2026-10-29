package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ImpactReviewedMappedNegativeTest {
    @Test
    fun `mapped return cannot claim a key slot`() {
        val f = ImpactReviewedMappedFixture()
        val key = ImpactValuePathSegmentDocument.MapEntryComponent(f.entry, ImpactMapEntrySlotDocument.KEY)
        assertFalse(key.isOnBasis(f.binding.basis))
        assertFalse(
            f.transfer
                .copy(
                    target = f.transfer.target.copy(valuePath = f.base.bounded(listOf(key) + f.source.valuePath.values))
                )
                .admitsEvidence()
        )
    }

    @Test
    fun `mapped return rejects another owner range role and epoch`() {
        val f = ImpactReviewedMappedFixture()
        val foreign =
            (f.binding.basis as ImpactSemanticBasisDocument.Published).copy(
                generation = (ImpactEvidenceRevisionDocument.parse(8) as Refinement.Refined).value
            )
        val sources =
            listOf(
                f.source.copy(role = ImpactValueRoleDocument.ExpressionResult),
                f.source.copy(executionOwner = ImpactExecutionOwnerDocument.Declaration),
                f.source.copy(range = f.base.range(51, 60)),
                f.source.copy(enclosing = f.source.enclosing.copy(basis = foreign)),
            )
        sources.forEach { assertFalse(f.transfer.copy(source = it).admitsEvidence()) }
    }

    @Test
    fun `mapped return keeps its exact key survival premise`() {
        val f = ImpactReviewedMappedFixture()
        val conditions: List<BoundedProtocolList<ImpactValueConditionDocument>> =
            listOf(
                EmptyImpactConditions,
                f.base.bounded(listOf(f.condition.copy(useRange = f.base.range(31, 90)))),
                f.base.bounded(listOf(f.condition, f.condition)),
            )
        conditions.forEach {
            assertFalse(f.transfer.copy(target = f.transfer.target.copy(conditions = it)).admitsEvidence())
        }
    }

    @Test
    fun `mapped return rejects wrong source artifact and overload`() {
        val base = ImpactReviewedValueFixture()
        val bad = base.text("f".repeat(64))
        val artifacts = ImpactReviewedMappedFixture().binding.artifacts as ImpactReviewedMapArtifactsDocument
        val changed =
            listOf(
                artifacts.copy(binaryArtifactSha256 = bad),
                artifacts.copy(sourceArtifactSha256 = bad),
                artifacts.copy(sourceEntrySha256 = bad),
                artifacts.copy(facadeSha256 = bad),
                artifacts.copy(implementationSha256 = bad),
                artifacts.copy(
                    sections =
                        (artifacts.sections as ImpactReviewedMapSectionsDocument.MapValues).let {
                            it.copy(mapValuesLines = it.mapValuesToLines)
                        }
                ),
                artifacts.copy(helpers = base.bounded(emptyList())),
            )
        changed.forEach { artifact ->
            assertFalse(ImpactReviewedMappedFixture { it.copy(artifacts = artifact) }.transfer.admitsEvidence())
        }
        assertFalse(
            ImpactReviewedMappedFixture {
                it.copy(
                    selected =
                        it.selected.copy(receiver = ImpactDeclaredCallbackReceiverDocument.Present(base.text("T")))
                )
            }
                .transfer
                .admitsEvidence()
        )
    }
}
