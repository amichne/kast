package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.generatedRequestSchema
import io.github.amichne.kast.protocol.contract.ChangeRejection
import io.github.amichne.kast.protocol.contract.ChangeRunDocument
import io.github.amichne.kast.protocol.contract.ChangeRunError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpStructuredResultsTest {
    @Test
    fun `packaged result schemas retain every canonical semantic contract`() {
        val names =
            io.github.amichne.kast.protocol.registry.PublicToolIdentity.entries.map { it.toolName } + "health_check"
        for (name in names) {
            assertEquals(McpResultSchemas.generate(name), McpStructuredResults.semanticSchemaFor(name), name)
            val document = Json.parseToJsonElement(resourceText("/mcp-result-schemas/$name.json")).jsonObject
            assertEquals(setOf("contractVersion", "name", "schema"), document.keys)
            assertEquals(name, document.getValue("name").jsonPrimitive.content)
            assertEquals(McpResultSchemas.generate(name), document.getValue("schema"), name)
        }
        assertThrows(IllegalStateException::class.java) { McpStructuredResults.semanticSchemaFor("unbound_tool") }
    }

    @Test
    fun `result lookup loads only its own schema and retains it for repeated requests`() {
        val available =
            mutableMapOf(
                "/mcp-result-schemas.json" to resourceText("/mcp-result-schemas.json"),
                "/mcp-result-schemas/replace_body.json" to resourceText("/mcp-result-schemas/replace_body.json"),
            )
        val resources = McpResultSchemas.Resources { path -> checkNotNull(available.remove(path)) { path } }
        assertThrows(IllegalStateException::class.java) { resources.schemaFor("unbound_tool") }
        val expected = McpResultSchemas.generate("replace_body")
        assertEquals(expected, resources.schemaFor("replace_body"))
        assertEquals(expected, resources.schemaFor("replace_body"))
        assertTrue(available.isEmpty())
    }

    @Test
    fun `result lookup fails closed for missing incompatible or mismatched resources`() {
        val indexPath = "/mcp-result-schemas.json"
        val schemaPath = "/mcp-result-schemas/replace_body.json"
        val index = resourceText(indexPath)
        val schema = resourceText(schemaPath)
        val version =
            Json.parseToJsonElement(index).jsonObject.getValue("contractVersion").jsonPrimitive.content.toInt()
        val cases =
            listOf(
                mapOf(
                    indexPath to
                        index.replaceFirst("\"contractVersion\":$version", "\"contractVersion\":${version + 1}"),
                    schemaPath to schema,
                ),
                mapOf(indexPath to index.replace("\"query_symbols\"", "\"unbound_tool\""), schemaPath to schema),
                mapOf(
                    indexPath to index,
                    schemaPath to
                        schema.replaceFirst("\"contractVersion\":$version", "\"contractVersion\":${version + 1}"),
                ),
                mapOf(
                    indexPath to index,
                    schemaPath to schema.replaceFirst("\"name\":\"replace_body\"", "\"name\":\"add_declaration\""),
                ),
            )
        for (available in cases) {
            val resources = McpResultSchemas.Resources { path -> available.getValue(path) }
            assertThrows(IllegalStateException::class.java) { resources.schemaFor("replace_body") }
        }
        val malformed = McpResultSchemas.Resources { path -> if (path == indexPath) index else "{" }
        assertThrows(SerializationException::class.java) { malformed.schemaFor("replace_body") }
        for (available in listOf(emptyMap(), mapOf(indexPath to index))) {
            val resources = McpResultSchemas.Resources { path -> available.getValue(path) }
            assertThrows(NoSuchElementException::class.java) { resources.schemaFor("replace_body") }
        }
    }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { path }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `every transport failure has a closed MCP schema without widening semantic contracts`() {
        val names =
            io.github.amichne.kast.protocol.registry.PublicToolIdentity.entries.map { it.toolName } + "health_check"
        for (failure in McpCallFailure.entries) {
            val document =
                mcpWire
                    .encodeToJsonElement(
                        McpTransportRejection.serializer(),
                        McpTransportRejection.CallFailure(McpCallError(failure, failure.nextAction)),
                    )
                    .jsonObject
            assertEquals("MCP_REJECTED", document.getValue("type").jsonPrimitive.content)
            assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
            for (name in names) {
                assertTrue(McpStructuredResults.validates(name, document), name)
                assertFalse(McpStructuredResults.validatesSemantic(name, document), name)
            }
        }
    }

    @Test
    fun `public query accepts exact source result without widening canonical query result`() {
        val fixture = io.github.amichne.kast.cli.LiveReadOutputSchemaTest()
        val basis =
            io.github.amichne.kast.kernel.EvidenceBasis.Published(
                (io.github.amichne.kast.kernel.EvidenceGeneration.parse(1)
                        as io.github.amichne.kast.kernel.Refinement.Refined)
                    .value
            )
        val evidence =
            io.github.amichne.kast.kernel.EvidenceEnvelope(
                io.github.amichne.kast.protocol.contract.CanonicalOperation.SOURCE_READ.id,
                basis,
                fixture.sourceResult(basis),
            )
        val document =
            with(fixture) {
                io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments.project(
                        io.github.amichne.kast.kernel.OperationOutcome.Complete(evidence)
                    )
                    .document()
            }
        assertTrue(McpStructuredResults.validates("query_symbols", document))
        fixture.assertRejects(io.github.amichne.kast.protocol.contract.CanonicalOperation.QUERY_RUN, document)
    }

    @Test
    fun `MCP result root preserves generated discriminator and finite rejection schema`() {
        val generated = generatedRequestSchema(ChangeRunDocument.serializer())
        val rooted = rootedResultSchema(generated)
        assertEquals(setOf("type", "anyOf", "discriminator"), rooted.keys)
        assertEquals(
            "status",
            rooted.getValue("discriminator").jsonObject.getValue("propertyName").jsonPrimitive.content,
        )
        assertEquals(generated.getValue("anyOf"), rooted.getValue("anyOf"))
        val statuses =
            rooted.getValue("anyOf").jsonArray.map {
                it.jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("status")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content
            }
        assertEquals(listOf("complete", "rejected"), statuses)
        val rejected =
            Json.encodeToJsonElement(
                    ChangeRunDocument.serializer(),
                    ChangeRunDocument.Rejected(ChangeRunError(ChangeRejection.PLANNING_REJECTED)),
                )
                .jsonObject
        assertTrue(McpStructuredResults.validates("replace_body", rejected))
        assertTrue(McpStructuredResults.validates("add_declaration", rejected))
    }

    @Test
    fun `unknown schema discriminator fields remain rejected`() {
        val raw = generatedRequestSchema(ChangeRunDocument.serializer()).toString()
        val malformed =
            Json.parseToJsonElement(
                    raw.replace("\"propertyName\":\"status\"", "\"propertyName\":\"status\",\"unrecognized\":true")
                )
                .jsonObject
        assertThrows(SerializationException::class.java) { rootedResultSchema(malformed) }
    }
}
