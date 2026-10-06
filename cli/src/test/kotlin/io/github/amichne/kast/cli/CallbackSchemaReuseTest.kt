package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackSchemaReuseTest {
    @Test
    fun `reusable callback definitions preserve every serializer constraint and closed variant`() {
        val schema = installedServerOutputSchema(CanonicalOperation.QUERY_RUN)
        val definitions = schema.getValue("\$defs").jsonObject
        for ((name, serializer) in
            listOf(
                "callbackObservation" to CanonicalQueryCliDocuments.callbackObservationSerializer,
                "callableObservation" to CanonicalQueryCliDocuments.callableObservationSerializer,
            )) {
            assertEquivalent(generatedRequestSchema(serializer), definitions.getValue(name), definitions)
        }
    }

    private fun assertEquivalent(expected: JsonElement, actual: JsonElement, definitions: JsonObject) {
        if (actual is JsonObject && "\$ref" in actual) {
            assertEquals(setOf("\$ref"), actual.keys)
            val reference = actual.getValue("\$ref").jsonPrimitive.content
            assertEquals(true, reference.startsWith("#/\$defs/"))
            assertEquivalent(expected, definitions.getValue(reference.removePrefix("#/\$defs/")), definitions)
            return
        }
        when (expected) {
            is JsonObject -> {
                actual as JsonObject
                assertEquals(expected.keys, actual.keys)
                expected.forEach { (key, child) -> assertEquivalent(child, actual.getValue(key), definitions) }
            }
            is JsonArray -> {
                actual as JsonArray
                assertEquals(expected.size, actual.size)
                expected.indices.forEach { assertEquivalent(expected[it], actual[it], definitions) }
            }
            is JsonPrimitive -> assertEquals(expected, actual)
        }
    }
}
