package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedFilterTest {
    @Test
    fun `predicate output and missing acceptance cannot establish retained elements`() {
        val f = ImpactReviewedFilterFixture()
        assertFalse(
            f.transfer
                .copy(
                    source =
                        f.source.copy(
                            role = ImpactValueRoleDocument.Return,
                            range = f.base.range(50, 60),
                            executionOwner = ImpactExecutionOwnerDocument.ReviewedCallback(f.binding),
                        )
                )
                .admitsEvidence()
        )
        assertFalse(
            f.transfer
                .copy(
                    target =
                        f.transfer.target.copy(conditions = f.base.bounded(f.transfer.target.conditions.values.take(1)))
                )
                .admitsEvidence()
        )
    }

    @Test
    fun `filter encoding keeps both premises without a false execution claim`() {
        val f = ImpactReviewedFilterFixture()
        val encoded = Json.encodeToString(ImpactReviewedValueTransferDocument.serializer(), f.transfer)
        val shape = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            "FILTERED_ELEMENTS",
            shape.getValue("binding").jsonObject.getValue("relation").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            listOf("REVIEWED_COLLECTION_NORMAL_RESULT", "REVIEWED_PREDICATE_ACCEPTS"),
            shape.getValue("target").jsonObject.getValue("conditions").jsonArray.map {
                it.jsonObject.getValue("type").jsonPrimitive.content
            },
        )
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(
                ImpactReviewedValueTransferDocument.serializer(),
                encoded.replace("\"actualExecution\":\"NOT_ESTABLISHED\"", "\"actualExecution\":\"EXECUTED\""),
            )
        }
    }

    @Test
    fun `filter preserves the receiver element suffix with qualified acceptance`() {
        val f = ImpactReviewedFilterFixture()
        assertTrue(f.transfer.admitsEvidence())
        assertEquals(f.source.valuePath, f.transfer.target.valuePath)
        assertEquals(ImpactRelationshipDocument.EXACT, f.transfer.binding.relation.relationship())
    }
}
