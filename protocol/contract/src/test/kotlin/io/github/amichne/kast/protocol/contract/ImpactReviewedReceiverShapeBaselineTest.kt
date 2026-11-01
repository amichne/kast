package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImpactReviewedReceiverShapeBaselineTest {
    @Test
    fun `top level run encodes absence without a type sentinel`() {
        val function = ImpactReviewedScopeFamilyFixture(ScopeFamily.TOP_RUN).binding.selected
        val receiver =
            Json.encodeToJsonElement(ImpactReviewedFunctionDocument.serializer(), function)
                .jsonObject
                .getValue("receiver")
                .jsonObject
        assertEquals(setOf("type"), receiver.keys)
        assertEquals("ABSENT", receiver.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `reviewed receiver encodes a required present tagged object`() {
        val function = ImpactReviewedScopeFixture().binding.selected
        val encoded = Json.encodeToJsonElement(ImpactReviewedFunctionDocument.serializer(), function).jsonObject
        val receiver = encoded.getValue("receiver").jsonObject
        assertEquals(setOf("type", "compilerType"), receiver.keys)
        assertEquals("PRESENT", receiver.getValue("type").jsonPrimitive.content)
        assertEquals("T", receiver.getValue("compilerType").jsonPrimitive.content)
    }

    @Test
    fun `tagged receiver rejects legacy strings and null sentinels`() {
        val function = ImpactReviewedScopeFixture().binding.selected
        val encoded = Json.encodeToString(ImpactReviewedFunctionDocument.serializer(), function)
        for (replacement in listOf("\"T\"", "null")) {
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(
                    ImpactReviewedFunctionDocument.serializer(),
                    encoded.replace(
                        "\"receiver\":{\"type\":\"PRESENT\",\"compilerType\":\"T\"}",
                        "\"receiver\":$replacement",
                    ),
                )
            }
        }
    }
}
