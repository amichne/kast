package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.EmptyImpactConditions
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactMapEntrySlotDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedGroupCallbacksDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedGroupProjectionTest {
    @Test
    fun `transformed bucket rejects selector output and a missing normal premise`() {
        val value = ImpactReviewedGroupFixture().transformed().impactDocument().componentValue()
        assertTrue(value.admitsEvidence())
        val entry = (value.binding.relation as ImpactReviewedValueRelationDocument.GroupPlacement).entry
        val callbacks = entry.callbacks as ImpactReviewedGroupCallbacksDocument.TransformedElements
        assertFalse(
            value
                .copy(
                    source =
                        value.source.copy(
                            executionOwner = ImpactExecutionOwnerDocument.ReviewedCallback(callbacks.selector)
                        )
                )
                .admitsEvidence()
        )
        assertFalse(value.copy(target = value.target.copy(conditions = EmptyImpactConditions)).admitsEvidence())
    }

    @Test
    fun `original and transformed values retain separate callback meanings`() {
        val fixture = ImpactReviewedGroupFixture()
        val original = fixture.original().impactDocument().componentValue()
        val transformed = fixture.transformed().impactDocument().componentValue()
        listOf(original, transformed).forEach { value ->
            assertTrue(value.admitsEvidence())
            val first = value.target.valuePath.values.first() as ImpactValuePathSegmentDocument.MapEntryComponent
            assertEquals(ImpactMapEntrySlotDocument.VALUE, first.slot)
            assertEquals(ImpactValuePathSegmentDocument.Element, value.target.valuePath.values[1])
            assertTrue(value.target.conditions.values.single() is ImpactValueConditionDocument.ReviewedGroupMembership)
        }
        assertEquals(original.source.valuePath.values.drop(1), original.target.valuePath.values.drop(2))
        assertEquals(transformed.source.valuePath.values, transformed.target.valuePath.values.drop(2))
    }

    @Test
    fun `transformed encoding keeps both callback owners and unknown execution`() {
        val value = ImpactReviewedGroupFixture().transformed().impactDocument().componentValue()
        val encoded = Json.encodeToJsonElement(ImpactReviewedValueTransferDocument.serializer(), value).jsonObject
        val relation = encoded.getValue("binding").jsonObject.getValue("relation").jsonObject
        assertEquals("GROUP_PLACEMENT", relation.getValue("type").jsonPrimitive.content)
        val callbacks = relation.getValue("entry").jsonObject.getValue("callbacks").jsonObject
        assertEquals(setOf("type", "selector", "transform"), callbacks.keys)
        assertEquals("TRANSFORMED_ELEMENTS", callbacks.getValue("type").jsonPrimitive.content)
        for ((owner, index) in listOf("selector" to "0", "transform" to "1")) {
            val binding = callbacks.getValue(owner).jsonObject
            assertEquals(index, binding.getValue("selected").jsonObject.getValue("formalIndex").jsonPrimitive.content)
            assertEquals("NOT_ESTABLISHED", binding.getValue("actualExecution").jsonPrimitive.content)
            assertEquals("NOT_ESTABLISHED", binding.getValue("invocationCount").jsonPrimitive.content)
        }
    }

    @Test
    fun `selector key retains representative qualification and rejects value placement`() {
        val value = ImpactReviewedGroupFixture().key().impactDocument().componentValue()
        assertTrue(value.admitsEvidence())
        val first = value.target.valuePath.values.first() as ImpactValuePathSegmentDocument.MapEntryComponent
        assertEquals(ImpactMapEntrySlotDocument.KEY, first.slot)
        assertTrue(
            value.target.conditions.values.single() is ImpactValueConditionDocument.ReviewedGroupKeyRepresentative
        )
        val swapped =
            BoundedProtocolList.create(
                    listOf(first.copy(slot = ImpactMapEntrySlotDocument.VALUE)) + value.target.valuePath.values.drop(1)
                )
                .componentValue()
        assertFalse(value.copy(target = value.target.copy(valuePath = swapped)).admitsEvidence())
    }
}
