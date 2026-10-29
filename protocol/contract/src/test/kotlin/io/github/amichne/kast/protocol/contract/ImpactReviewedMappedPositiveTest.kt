package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedMappedPositiveTest {
    @Test
    fun `actual callback return occupies a qualified value slot with its suffix`() {
        val f = ImpactReviewedMappedFixture()
        assertTrue(f.transfer.admitsEvidence())
        assertTrue(f.transfer.target.admitsValueState())
        assertEquals(f.source.valuePath.values, f.transfer.target.valuePath.values.drop(1))
        val encoded = Json.encodeToString(f.transfer)
        val document = Json.parseToJsonElement(encoded).jsonObject
        val relationship = document.getValue("binding").jsonObject
        assertEquals(
            "MAPPED_VALUE",
            relationship.getValue("relation").jsonObject.getValue("type").jsonPrimitive.content,
        )
        val authority = relationship.getValue("authority").jsonObject
        assertEquals("NOT_ESTABLISHED", authority.getValue("actualExecution").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", authority.getValue("invocationCount").jsonPrimitive.content)
        val component = document.getValue("target").jsonObject.getValue("valuePath").jsonArray.first().jsonObject
        assertEquals("VALUE", component.getValue("slot").jsonPrimitive.content)
        val origin = component.getValue("entry").jsonObject
        assertEquals("MAPPED_RETURN", origin.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("type", "family", "returnRange", "returnExecution", "selection"), origin.keys)
        assertEquals(f.transfer, Json.decodeFromString<ImpactReviewedValueTransferDocument>(encoded))
    }
}
