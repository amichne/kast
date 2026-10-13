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

/** Actual typed production captures exercise the advertised schema without a semantic provider. */
class RetainedRelationGraphSchemaTest {
    private val pages =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/query-compact-graph-pages.json")).readText())
            .jsonArray
            .map { it.jsonObject.getValue("document").jsonObject }
    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(installedServerOutputSchema(CanonicalOperation.QUERY_RUN).toString())

    @Test
    fun `all captured compact pages admit every expected retained occurrence`() {
        assertEquals(EXPECTED_PAGES, pages.size)
        assertEquals(EXPECTED_OCCURRENCES, pages.sumOf { it.getValue("items").jsonArray.size })
        for (page in pages) {
            val errors = schema.validate(completedSchemaEnvelope(page), InputFormat.JSON)
            assertTrue(errors.isEmpty(), errors.toString())
            val detail = page.getValue("relation_graph").jsonObject.getValue("detail").jsonObject
            assertEquals("true", detail.getValue("verbose").jsonPrimitive.content)
            assertEquals(
                "READ_RESULT",
                detail.getValue("request").jsonObject.getValue("type").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `unknown graph variants invalid identities and missing required detail reject`() {
        val page = pages.first()
        val valid = completedSchemaEnvelope(page)
        val row = page.getValue("items").jsonArray.first().jsonObject.getValue("row_id").jsonPrimitive.content
        val malformed =
            listOf(
                valid.replace("DECLARATION_TO_TARGET", "REVERSED"),
                valid.replace("\"type\":\"declaration-owned\"", "\"type\":\"name-owned\""),
                valid.replace("node:v1:", "node:v2:"),
                valid.replace("witness:v1:", "witness:v2:"),
                valid.replace(row, row.dropLast(1)),
                valid.replace("\"type\":\"READ_RESULT\",", ""),
                valid.replace("\"output\":{\"type\":\"OCCURRENCES\"}", "\"output\":{}"),
                valid.replace("\"verbose\":true", "\"verbose\":null"),
                valid.replace("\"relation_graph\":{", "\"relation_graph\":{\"unknown\":true,"),
            )
        for ((index, document) in malformed.withIndex()) {
            assertTrue(document != valid, "The negative control must change the captured document")
            assertTrue(schema.validate(document, InputFormat.JSON).isNotEmpty(), "Malformed graph case $index")
        }
    }

    private companion object {
        const val EXPECTED_PAGES = 27
        const val EXPECTED_OCCURRENCES = 53
    }
}
