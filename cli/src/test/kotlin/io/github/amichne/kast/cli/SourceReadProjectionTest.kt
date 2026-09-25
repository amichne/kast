package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.ExactSymbolSelector
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SimpleSourceEntities
import io.github.amichne.kast.protocol.contract.SimpleSourceRegion
import io.github.amichne.kast.protocol.contract.SimpleSourceText
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.contract.SourceReadSimpleRequest
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceReadProjectionTest {
    @Test
    fun `published source schema and admission agree on one field exact request`() {
        val schema = schemaRegistry.getSchema(projectionTools().tool("source_read").getValue("inputSchema").toString())
        val symbol = "exact:v5:${"A".repeat(21)}Q"
        val exact = (ExactSymbolSelector.parse(symbol) as io.github.amichne.kast.kernel.Refinement.Refined).value
        val input = Json.encodeToString(SourceReadSimpleRequest.serializer(), SourceReadSimpleRequest(exact))
        val override =
            Json.encodeToString(
                SourceReadSimpleRequest.serializer(),
                SourceReadSimpleRequest(
                    symbol = exact,
                    region = SimpleSourceRegion.BODY,
                    text =
                        SimpleSourceText.Window(
                            maximumBytes =
                                (SourceTextByteLimitDocument.parse(12_000)
                                        as io.github.amichne.kast.kernel.Refinement.Refined)
                                    .value
                        ),
                    entities =
                        SimpleSourceEntities.Declarations(
                            (SourceEntityLimitDocument.parse(50) as io.github.amichne.kast.kernel.Refinement.Refined)
                                .value
                        ),
                    format = SourceReadFormatDocument.EXPANDED,
                ),
            )
        val invalid =
            Json.encodeToString(
                MixedSourceInput.serializer(),
                MixedSourceInput(
                    symbol,
                    SourceReadAnchorDocument.Symbol(
                        (ProtocolText.parse(symbol) as io.github.amichne.kast.kernel.Refinement.Refined).value
                    ),
                ),
            )

        assertTrue(schema.validate(input, InputFormat.JSON).isEmpty())
        assertTrue(schema.validate(override, InputFormat.JSON).isEmpty())
        assertTrue(
            io.github.amichne.kast.protocol.contract.SourceRequestIngress.decode(
                Json.parseToJsonElement(input),
                Json { classDiscriminator = "type" },
            ) is io.github.amichne.kast.kernel.Refinement.Refined
        )
        assertFalse(schema.validate(invalid, InputFormat.JSON).isEmpty())
    }

    @Test
    fun `generated source examples execute through schema and server ingress`() {
        val document = Json.parseToJsonElement(mintlifyCallableReference().value).jsonObject
        val media =
            document
                .getValue("paths")
                .jsonObject
                .getValue("/callables/source_read")
                .jsonObject
                .getValue("post")
                .jsonObject
                .getValue("requestBody")
                .jsonObject
                .getValue("content")
                .jsonObject
                .getValue("application/json")
                .jsonObject
        val examples = media.getValue("examples").jsonObject
        val schema = schemaRegistry.getSchema(projectionTools().tool("source_read").getValue("inputSchema").toString())
        assertEquals(setOf("exactSymbol", "callableBody"), examples.keys)
        examples.values.forEach { example ->
            val input = example.jsonObject.getValue("value")
            assertTrue(schema.validate(input.toString(), InputFormat.JSON).isEmpty())
            assertTrue(
                io.github.amichne.kast.protocol.contract.SourceRequestIngress.decode(
                    input,
                    Json { classDiscriminator = "type" },
                ) is io.github.amichne.kast.kernel.Refinement.Refined
            )
        }
    }

    @Test
    fun `source schema branches are disjoint closed and explicit about required fields`() {
        val schema = projectionTools().tool("source_read").getValue("inputSchema").jsonObject
        val alternatives = schema.getValue("anyOf").jsonArray.map(JsonElement::jsonObject)
        assertEquals(2, alternatives.size)
        val branches = alternatives[0].getValue("oneOf").jsonArray.map(JsonElement::jsonObject)
        assertEquals(2, branches.size)
        assertEquals(
            setOf("symbol"),
            branches[0].getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertTrue("anchor" !in branches[0].getValue("properties").jsonObject)
        assertTrue("symbol" !in branches[1].getValue("properties").jsonObject)
        assertEquals(
            setOf("anchor", "region", "entities", "text"),
            branches[1].getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val public = alternatives[1]
        assertEquals(setOf("anchor"), public.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue("symbol" !in public.getValue("properties").jsonObject)
        assertTrue("entityLimit" !in public.getValue("properties").jsonObject)
        assertEquals(
            "object",
            branches[1]
                .getValue("properties")
                .jsonObject
                .getValue("region")
                .jsonObject
                .getValue("anyOf")
                .jsonArray
                .first()
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "string",
            public
                .getValue("properties")
                .jsonObject
                .getValue("region")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        fun assertClosed(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    if ((value["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "object") {
                        assertEquals(false, value["additionalProperties"]?.jsonPrimitive?.booleanOrNull)
                        assertTrue(value["properties"] is JsonObject)
                    }
                    value.values.forEach(::assertClosed)
                }
                is kotlinx.serialization.json.JsonArray -> value.forEach(::assertClosed)
                else -> Unit
            }
        }
        assertClosed(schema)
    }

    private fun projectionTools(): List<JsonObject> = InstalledServerProjectionTest().projectionTools()

    private fun List<JsonObject>.tool(name: String): JsonObject = single {
        it.getValue("name").jsonPrimitive.content == name
    }

    companion object {
        private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    }
}

@Serializable private data class MixedSourceInput(val symbol: String, val anchor: SourceReadAnchorDocument)
