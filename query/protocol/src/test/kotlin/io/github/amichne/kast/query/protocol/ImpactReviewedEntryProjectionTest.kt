package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactMapEntrySlotDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryDeclarationArtifactDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryReadOrderDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryReadPrefixDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedInputEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedPreservedKeyOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedEntryProjectionTest {
    @Test
    fun `second component keeps the normal prefix premise and underscore omission`() {
        val fixture = ImpactReviewedEntryProjectionFixture()
        val transfer = fixture.destructuredSecond().impactDocument().componentValue()
        assertTrue(transfer.admitsEvidence())
        assertTrue(transfer.target.conditions.values.last() is ImpactValueConditionDocument.ReviewedEntryPrefixNormal)
        val omitted =
            transfer.target.copy(
                conditions = BoundedProtocolList.create(transfer.target.conditions.values.dropLast(1)).componentValue()
            )
        assertFalse(transfer.copy(target = omitted).admitsEvidence())
        val skipped = fixture.destructuredSecond(true).impactDocument().componentValue()
        assertTrue(skipped.admitsEvidence())
        val authority = skipped.binding.authority as ImpactReviewedCallValueBindingDocument
        assertEquals(
            ImpactReviewedEntryReadPrefixDocument.Empty,
            (authority.entryReadOrder as ImpactReviewedEntryReadOrderDocument.Destructured).prefix,
        )
        assertFalse(
            skipped.target.conditions.values.any { it is ImpactValueConditionDocument.ReviewedEntryPrefixNormal }
        )
    }

    @Test
    fun `input slots share the callback family and preserve nested suffixes`() {
        val fixture = ImpactReviewedEntryProjectionFixture()
        val inputs = listOf(true, false).map { fixture.input(it).impactDocument().componentValue() }
        inputs.forEach { input ->
            assertTrue(input.admitsEvidence())
            assertEquals(input.source.valuePath.values.drop(1), input.target.valuePath.values.drop(1))
            assertEquals(input.source.conditions, input.target.conditions)
        }
        val origins = inputs.map {
            (it.target.valuePath.values.first() as ImpactValuePathSegmentDocument.MapEntryComponent).entry
                as ImpactReviewedInputEntryOriginDocument
        }
        assertEquals(origins.first().family, origins.last().family)
    }

    @Test
    fun `entry reads keep metadata or JVM authority and the stable component premise`() {
        val fixture = ImpactReviewedEntryProjectionFixture()
        listOf(true, false).forEach { component ->
            listOf(true, false).forEach { key ->
                val read = fixture.read(key, component).impactDocument().componentValue()
                assertTrue(read.admitsEvidence())
                assertEquals(
                    !component,
                    read.binding.authority.artifacts is ImpactReviewedEntryDeclarationArtifactDocument,
                )
                assertEquals(read.source.valuePath.values.drop(1), read.target.valuePath.values)
                val added =
                    read.target.conditions.values.last() as ImpactValueConditionDocument.ReviewedEntryComponentStable
                assertEquals(if (key) ImpactMapEntrySlotDocument.KEY else ImpactMapEntrySlotDocument.VALUE, added.slot)
            }
        }
    }

    @Test
    fun `preserved key has its own origin and survival premise`() {
        val transfer = ImpactReviewedEntryProjectionFixture().preserved().impactDocument().componentValue()
        assertTrue(transfer.admitsEvidence())
        val relation = transfer.binding.relation as ImpactReviewedValueRelationDocument.PreservedKey
        val path = transfer.target.valuePath.values.first() as ImpactValuePathSegmentDocument.MapEntryComponent
        assertEquals(ImpactMapEntrySlotDocument.KEY, path.slot)
        assertTrue(path.entry is ImpactReviewedPreservedKeyOriginDocument)
        assertEquals(relation.entry, path.entry)
        assertEquals(
            relation.entry,
            (transfer.target.conditions.values.last() as ImpactValueConditionDocument.ReviewedPreservedKeySurvives)
                .entry,
        )
    }
}
