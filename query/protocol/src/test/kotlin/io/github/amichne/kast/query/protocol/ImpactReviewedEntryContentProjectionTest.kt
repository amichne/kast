package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntrySelectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedEntryContentProjectionTest {
    @Test
    fun `content selection survives input getter and mapped return`() {
        val fixture = ImpactReviewedEntryProjectionFixture()
        val input = fixture.contentInput(false).impactDocument().componentValue()
        val read = fixture.read(false, content = true).impactDocument().componentValue()
        val output = fixture.mappedContentReturn().impactDocument().componentValue()
        listOf(input, read, output, fixture.preservedContent().impactDocument().componentValue()).forEach {
            assertTrue(it.admitsEvidence())
        }
        assertTrue(output.source.valuePath.values.isEmpty())
        assertEquals(
            ImpactReviewedEntrySelectionDocument.CONTENT,
            (output.binding.relation as ImpactReviewedValueRelationDocument.MappedValue).entry.selection,
        )
        val stable = output.source.conditions.values.last() as ImpactValueConditionDocument.ReviewedEntryComponentStable
        assertTrue(stable.origin.valuePath.values.first() is ImpactValuePathSegmentDocument.MapEntryComponent)
    }

    @Test
    fun `mapped return cannot replace inherited content with component identity`() {
        val original = ImpactReviewedEntryProjectionFixture().mappedContentReturn().impactDocument().componentValue()
        assertTrue(original.admitsEvidence())
        assertFalse(original.withComponentReturn().admitsEvidence())
    }
}

internal fun ImpactReviewedValueTransferDocument.withComponentReturn(): ImpactReviewedValueTransferDocument {
    val relation = binding.relation as ImpactReviewedValueRelationDocument.MappedValue
    val entry = relation.entry.copy(selection = ImpactReviewedEntrySelectionDocument.COMPONENT)
    val last = target.conditions.values.last() as ImpactValueConditionDocument.ReviewedMappedKeySurvives
    return copy(
        binding = binding.copy(relation = relation.copy(entry = entry)),
        target =
            target.copy(
                valuePath =
                    BoundedProtocolList.create(
                            listOf(
                                ImpactValuePathSegmentDocument.MapEntryComponent(
                                    entry,
                                    io.github.amichne.kast.protocol.contract.ImpactMapEntrySlotDocument.VALUE,
                                )
                            ) + source.valuePath.values
                        )
                        .componentValue(),
                conditions =
                    BoundedProtocolList.create(target.conditions.values.dropLast(1) + last.copy(entry = entry))
                        .componentValue(),
            ),
    )
}
