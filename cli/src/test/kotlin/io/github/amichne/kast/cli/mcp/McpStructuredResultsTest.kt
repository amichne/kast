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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpStructuredResultsTest {
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
