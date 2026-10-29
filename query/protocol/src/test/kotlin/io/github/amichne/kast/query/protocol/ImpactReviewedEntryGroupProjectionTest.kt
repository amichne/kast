package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryReadOrderDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedEntryGroupProjectionTest {
    @Test
    fun `detached prefix from another declaration cannot authorize a second component`() {
        val transfer = ImpactReviewedEntryProjectionFixture().destructuredSecond().impactDocument().componentValue()
        assertTrue(transfer.admitsEvidence())
        val authority = transfer.binding.authority as ImpactReviewedCallValueBindingDocument
        val order = authority.entryReadOrder as ImpactReviewedEntryReadOrderDocument.Destructured
        val otherGroup = componentRange(128, 145).impactDocument().componentValue()
        val forged = authority.copy(entryReadOrder = order.copy(declarationRange = otherGroup))
        assertFalse(transfer.withAuthority(forged).admitsEvidence())
    }

    @Test
    fun `matching declaration range survives the encoded read order`() {
        val transfer = ImpactReviewedEntryProjectionFixture().destructuredSecond().impactDocument().componentValue()
        assertTrue(transfer.admitsEvidence())
        val authority = transfer.binding.authority as ImpactReviewedCallValueBindingDocument
        val order = authority.entryReadOrder as ImpactReviewedEntryReadOrderDocument.Destructured
        val expected = componentRange(120, 145).impactDocument().componentValue()
        assertEquals(expected, order.declarationRange)
        val encoded = Json.encodeToJsonElement(ImpactReviewedEntryReadOrderDocument.serializer(), order).jsonObject
        assertEquals(setOf("type", "declarationRange", "prefix"), encoded.keys)
        val group = encoded.getValue("declarationRange").jsonObject
        assertEquals("120", group.getValue("start").jsonPrimitive.content)
        assertEquals("145", group.getValue("end").jsonPrimitive.content)
    }

    private fun ImpactReviewedValueTransferDocument.withAuthority(
        authority: ImpactReviewedCallValueBindingDocument
    ): ImpactReviewedValueTransferDocument =
        copy(
            binding = binding.copy(authority = authority),
            target =
                target.copy(
                    conditions =
                        BoundedProtocolList.create(
                                target.conditions.values.map { condition ->
                                    when (condition) {
                                        is ImpactValueConditionDocument.ReviewedEntryComponentStable ->
                                            condition.copy(binding = authority)
                                        is ImpactValueConditionDocument.ReviewedEntryPrefixNormal ->
                                            condition.copy(binding = authority)
                                        else -> condition
                                    }
                                }
                            )
                            .componentValue()
                ),
        )
}
