package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.EmptyImpactConditions
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedSelectionProjectionTest {
    @Test
    fun `mapNotNull rejects a direct expression shortcut and a missing premise`() {
        val transfer = ImpactReviewedSelectionFixture().nonNullResult().impactDocument().componentValue()
        assertTrue(transfer.admitsEvidence())
        assertFalse(
            transfer
                .copy(source = transfer.source.copy(role = ImpactValueRoleDocument.ExpressionResult))
                .admitsEvidence()
        )
        assertFalse(transfer.copy(target = transfer.target.copy(conditions = EmptyImpactConditions)).admitsEvidence())
    }

    @Test
    fun `mapNotNull keeps actual return suffix and a nonnull acceptance premise`() {
        val fixture = ImpactReviewedSelectionFixture()
        val input = fixture.nonNullInput().impactDocument().componentValue()
        val transfer = fixture.nonNullResult().impactDocument().componentValue()
        assertTrue(input.admitsEvidence())
        assertTrue(transfer.admitsEvidence())
        assertEquals(ImpactReviewedValueRelationDocument.NonNullCallbackResult, transfer.binding.relation)
        assertEquals(transfer.source.valuePath.values, transfer.target.valuePath.values.drop(1))
        assertTrue(
            transfer.target.conditions.values.single() is ImpactValueConditionDocument.ReviewedCallbackResultNonNull
        )
        val encoded = Json.encodeToJsonElement(ImpactReviewedValueTransferDocument.serializer(), transfer).jsonObject
        val authority = encoded.getValue("binding").jsonObject.getValue("authority").jsonObject
        assertEquals("NOT_ESTABLISHED", authority.getValue("actualExecution").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", authority.getValue("invocationCount").jsonPrimitive.content)
        assertEquals(
            "NON_NULL_CALLBACK_RESULT",
            encoded
                .getValue("binding")
                .jsonObject
                .getValue("relation")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `filter input remains distinct from its qualified retained elements`() {
        val fixture = ImpactReviewedSelectionFixture()
        val input = fixture.filterInput().impactDocument().componentValue()
        val retained = fixture.filtered().impactDocument().componentValue()
        assertTrue(input.admitsEvidence())
        assertTrue(retained.admitsEvidence())
        assertEquals(input.binding.authority, retained.binding.authority)
        assertEquals(retained.source.valuePath, retained.target.valuePath)
        assertTrue(retained.target.conditions.values[0] is ImpactValueConditionDocument.ReviewedCollectionNormalResult)
        assertTrue(retained.target.conditions.values[1] is ImpactValueConditionDocument.ReviewedPredicateAccepts)
        assertFalse(retained.copy(source = input.target).admitsEvidence())
    }
}
