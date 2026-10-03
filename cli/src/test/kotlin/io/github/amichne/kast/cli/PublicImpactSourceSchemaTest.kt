package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PublicImpactSourceSchemaTest {
    @Test
    fun `public impact model and seed syntax reuse canonical serializer constraints`() {
        val expected = generatedRequestSchema(QueryImpactSourceDocument.serializer()).getValue("properties").jsonObject
        val definitions = PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS).getValue("\$defs").jsonObject
        val impact = definitions["ImpactSource"]
        assertNotNull(impact, expected.toString())
        val actual = impact!!.jsonObject.getValue("properties").jsonObject
        for (property in listOf("seeds", "declarations", "models")) {
            assertSchemaEquals(expected.getValue(property), actual.getValue(property), definitions, property)
        }
        assertSchemaEquals(
            expected.getValue("requestedSites").jsonObject.getValue("items"),
            actual.getValue("requestedSites").jsonObject.getValue("items"),
            definitions,
            "requestedSites.items",
        )
    }

    private fun assertSchemaEquals(expected: JsonElement, actual: JsonElement, definitions: JsonObject, path: String) {
        if (actual is JsonObject && "\$ref" in actual) {
            val name = actual.getValue("\$ref").jsonPrimitive.content.removePrefix("#/\$defs/")
            assertSchemaEquals(expected, definitions.getValue(name), definitions, path)
            return
        }
        when (expected) {
            is JsonArray -> {
                assertEquals(expected.size, actual.jsonArray.size, path)
                expected.forEachIndexed { index, value ->
                    assertSchemaEquals(value, actual.jsonArray[index], definitions, "$path[$index]")
                }
            }
            is JsonObject -> {
                val metadata = setOf("x-kotlin-type", "default", "discriminator", "description")
                assertEquals(
                    expected.keys.filter { it !in metadata }.toSet(),
                    actual.jsonObject.keys.filter { it !in metadata }.toSet(),
                    path,
                )
                expected
                    .filterKeys { it !in metadata }
                    .forEach { (key, value) ->
                        assertSchemaEquals(value, actual.jsonObject.getValue(key), definitions, "$path.$key")
                    }
            }
            else -> assertEquals(expected, actual, path)
        }
    }
}
