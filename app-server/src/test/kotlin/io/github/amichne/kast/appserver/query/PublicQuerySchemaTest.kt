package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.appserver.schema.CompiledJsonSchema
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicQuerySchemaTest {
    private val authoring = read("query.schema.json")

    @Test
    fun `all objects are closed and use uppercase type enums`() {
        visit(authoring) { node ->
            if (node["properties"] != null) {
                assertEquals(JsonPrimitive(false), node["additionalProperties"])
                assertTrue(JsonPrimitive("type") in node.getValue("required").jsonArray)
                val tag = node.getValue("properties").jsonObject.getValue("type").jsonObject
                assertNull(tag["const"])
                assertTrue(
                    tag.getValue("enum").jsonArray.all { it.jsonPrimitive.content.matches(Regex("[A-Z][A-Z0-9_]*")) }
                )
            }
            if (node["anyOf"] != null) {
                assertEquals(
                    "type",
                    node.getValue("discriminator").jsonObject.getValue("propertyName").jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun `every enumerated value is caps case and scope needs no union keywords`() {
        visit(authoring) { node ->
            node["enum"]?.jsonArray?.forEach { value ->
                assertTrue(value.jsonPrimitive.content.matches(Regex("[A-Z][A-Z0-9_]*")), value.toString())
            }
        }
        val scope = authoring.getValue("\$defs").jsonObject.getValue("Scope").jsonObject
        visit(scope) { node ->
            assertTrue(node.keys.intersect(setOf("anyOf", "allOf", "oneOf", "if", "then", "else")).isEmpty())
        }
        assertEquals(setOf("type", "value", "containment", "sourceSets"), scope.getValue("properties").jsonObject.keys)
    }

    @Test
    fun `examples and defaults satisfy the exact node that owns them`() {
        visit(authoring) { node ->
            val schema = compile(JsonObject(node + ("\$defs" to authoring.getValue("\$defs"))))
            node["examples"]?.jsonArray?.forEach { example ->
                assertTrue(schema.admit(example) is Validation.Validated, example.toString())
            }
            node["default"]?.let { default ->
                assertTrue(schema.admit(default) is Validation.Validated, default.toString())
            }
        }
    }

    @Test
    fun `negative corpus is rejected by schema and public codec`() {
        val rejected =
            listOf(
                "{\"from\":{\"type\":\"ALL\"}}",
                "{\"type\":\"QUERY\"}",
                "{\"type\":\"query\",\"from\":{\"type\":\"ALL\"}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"symbols\"}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"all\"}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"SEARCH\"}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"SEARCH\",\"query\":\" \\t\"}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"SEARCH\",\"query\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"SEARCH\",\"query\":null}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"kinds\":[]}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"kinds\":[\"class\",\"class\"]}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"kinds\":[\"constructor\"]}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"SEARCH\",\"query\":\"OrderService\"},\"limit\":10}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"SEARCH\",\"query\":\"OrderService\"},\"execution\":{\"kind\":\"exhaustive\"}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"SEARCH\",\"query\":\"OrderService\"},\"output\":{\"type\":\"candidates\",\"fields\":[]}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"SEARCH\",\"query\":\"Order\",\"refs\":[\"exact:v2:abc\"]}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"REFS\",\"refs\":[]}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"REFS\",\"refs\":[\"candidate:v2:abc\"]}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"REFS\",\"refs\":[\"source:v2:abc\"]}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"REFS\",\"refs\":[{\"kind\":\"exact-symbol\",\"token\":\"exact:v2:abc\"}]}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\"},\"steps\":[{\"type\":\"INSPECT\"}]}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\"},\"steps\":[{\"op\":\"distinct\"}]}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\"},\"steps\":[{\"type\":\"SORT\"}]}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\"},\"steps\":[{\"type\":\"FILTER\",\"visibility\":[]}]}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\"},\"steps\":[{\"type\":\"FILTER\",\"visibility\":[\"public\",\"public\"]}]}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\"},\"steps\":[{\"type\":\"EXPAND\",\"relation\":\"type_uses\"}]}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\"},\"select\":[\"name\",\"name\"]}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\"},\"select\":[\"compilerEvidence\"]}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"scope\":{\"type\":\"SCOPE\",\"sourceSets\":[]}}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"scope\":{\"type\":\"SCOPE\",\"sourceSets\":[\"main\",\"main\"]}}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"scope\":{\"sourceSets\":[\"main\"]}}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"scope\":{\"type\":\"SCOPE\",\"directory\":{\"type\":\"DIRECTORY\",\"path\":\"/tmp\"}}}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"scope\":{\"type\":\"SCOPE\",\"directory\":{\"type\":\"DIRECTORY\",\"path\":\"a/../b\"}}}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"scope\":{\"type\":\"SCOPE\",\"directory\":{\"type\":\"DIRECTORY\",\"path\":\"a/./b\"}}}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"scope\":{\"type\":\"SCOPE\",\"directory\":{\"type\":\"DIRECTORY\",\"path\":\"a//b\"}}}}",
                "{\"type\":\"QUERY\",\"from\":{\"type\":\"ALL\",\"scope\":{\"type\":\"SCOPE\",\"directory\":{\"type\":\"DIRECTORY\",\"path\":\"a/   /b\"}}}}",
            )
        rejected.forEach { raw ->
            val value = Json.parseToJsonElement(raw)
            assertTrue(PublicQueryContract.schema.admit(value) is Validation.Rejected, raw)
            assertTrue(PublicQueryContract.admit(value) is Refinement.Rejected, raw)
        }
    }

    @Test
    fun `provider profiles contain no authoring annotations and strict fields are required`() {
        listOf("query.parameters.json", "query.openai-parameters.json").forEach { name ->
            visit(read(name)) { node ->
                assertTrue(
                    node.keys
                        .intersect(
                            setOf(
                                "\$id",
                                "\$schema",
                                "discriminator",
                                "examples",
                                "default",
                                "title",
                                "x-kotlin-type",
                                "x-kotlin-variants",
                            )
                        )
                        .isEmpty()
                )
                if (name == "query.openai-parameters.json" && node["properties"] != null) {
                    assertEquals(
                        node.getValue("properties").jsonObject.keys,
                        node.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    )
                }
            }
        }
    }

    private fun read(name: String): JsonObject =
        requireNotNull(PublicQueryContract::class.java.getResourceAsStream(name)).bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }

    private fun compile(schema: JsonObject): CompiledJsonSchema =
        when (val result = CompiledJsonSchema.compile(schema)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error("Invalid test schema: ${result.failure}")
        }

    private fun visit(node: JsonObject, block: (JsonObject) -> Unit) {
        block(node)
        listOf("properties", "\$defs").forEach { key ->
            node[key]?.jsonObject?.values?.forEach { visit(it.jsonObject, block) }
        }
        node["anyOf"]?.jsonArray?.forEach { visit(it.jsonObject, block) }
        node["items"]?.let { visit(it.jsonObject, block) }
    }
}
