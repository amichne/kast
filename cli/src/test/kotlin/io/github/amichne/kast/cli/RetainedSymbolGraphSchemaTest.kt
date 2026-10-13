package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetainedSymbolGraphSchemaTest {
    private val pages =
        Json.parseToJsonElement(
                checkNotNull(javaClass.getResource("/query-compact-symbol-graph-pages.json")).readText()
            )
            .jsonArray
            .map { it.jsonObject.getValue("document").jsonObject }
    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(installedServerOutputSchema(CanonicalOperation.QUERY_RUN).toString())

    @Test
    fun `all minimal symbol pages admit exact identities and actual current selection`() {
        assertEquals(27, pages.size)
        assertEquals(53, pages.sumOf { it.getValue("items").jsonArray.size })
        for (page in pages) {
            val errors = schema.validate(completedSchemaEnvelope(page), InputFormat.JSON)
            assertTrue(errors.isEmpty(), errors.toString())
            val graph = page.getValue("relation_graph").jsonObject
            val output =
                graph.getValue("detail").jsonObject.getValue("request").jsonObject.getValue("output").jsonObject
            assertEquals("SYMBOLS", output.getValue("type").jsonPrimitive.content)
            assertTrue(output.getValue("fields").jsonArray.isEmpty())
            for (node in graph.getValue("nodes").jsonArray) {
                val symbol = node.jsonObject.getValue("symbol").jsonObject
                assertTrue(
                    symbol
                        .getValue("compiler_identity")
                        .jsonPrimitive
                        .content
                        .startsWith("canonical-signature-sha256-v1|")
                )
            }
        }
    }

    @Test
    fun `unknown connections missing proof and unknown selection reject`() {
        val valid = completedSchemaEnvelope(pages.first())
        val malformed =
            listOf(
                valid.replace("\"type\":\"reference-backed\"", "\"type\":\"name-backed\""),
                valid.replace("connection:v1:", "connection:v2:"),
                valid.replace("witness:v1:", "witness:v2:"),
                valid.replace("\"type\":\"SYMBOLS\",\"fields\":[]", "\"type\":\"SYMBOLS\""),
                valid.replace("\"type\":\"SYMBOLS\",\"fields\":[]", "\"type\":\"SYMBOLS\",\"fields\":[\"BODY\"]"),
                valid.replace("\"type\":\"exact-symbol-node\"", "\"type\":\"name-node\""),
            )
        for ((index, document) in malformed.withIndex()) {
            assertTrue(document != valid, "The negative control must change the captured document")
            assertTrue(schema.validate(document, InputFormat.JSON).isNotEmpty(), "Malformed symbol graph case $index")
        }
    }
}
