package io.github.amichne.kast.appserver.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolSchemaPolicyTest {
    @Test
    fun `public tool enums use upper case values and singleton defaults`() {
        val authored = read("tools.schema.json").jsonObject
        val full =
            listOf(
                authored,
                read("query_symbols.parameters.json").jsonObject,
                read("check_diagnostics.parameters.json").jsonObject,
            )
        val strict =
            listOf(
                read("query_symbols.openai-parameters.json").jsonObject,
                read("check_diagnostics.openai-parameters.json").jsonObject,
            )
        (full + strict).forEach { schema ->
            visit(schema) { node ->
                val values = node["enum"]?.jsonArray ?: return@visit
                assertTrue(
                    values.all { it is JsonNull || it.jsonPrimitive.content.matches(Regex("[A-Z][A-Z0-9_]*")) },
                    node.toString(),
                )
                if (schema in full && values.size == 1) {
                    assertEquals(values.single(), node["default"], node.toString())
                }
            }
        }
        visit(authored) { node ->
            val properties = node["properties"]?.jsonObject ?: return@visit
            assertFalse("action" in properties)
            if (properties.values.any { it.jsonObject["enum"]?.jsonArray?.size == 1 }) {
                assertEquals(1, properties.getValue("type").jsonObject.getValue("enum").jsonArray.size)
                assertTrue(node.getValue("required").jsonArray.any { it.jsonPrimitive.content == "type" })
            }
        }
    }

    private fun read(name: String) =
        requireNotNull(PublicToolContract::class.java.getResourceAsStream(name)).bufferedReader().use {
            Json.parseToJsonElement(it.readText())
        }

    private fun visit(node: JsonObject, check: (JsonObject) -> Unit) {
        check(node)
        listOf("properties", "\$defs").forEach { node[it]?.jsonObject?.values?.forEach { visit(it.jsonObject, check) } }
        node["anyOf"]?.jsonArray?.forEach { visit(it.jsonObject, check) }
        node["items"]?.let { visit(it.jsonObject, check) }
    }
}
