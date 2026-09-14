package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.ToolPresentation
import io.github.amichne.kast.appserver.schema.canonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodexToolTerminalReplyTest {
    @Test
    fun `every terminal failure remains one parseable JSON item with its exact finite code`() {
        val expected = listOf("CATALOG_INCOMPATIBLE", "BROKER_OVERLOADED_IN_FLIGHT_CALLS_PER_CONNECTION",
            "DUPLICATE_INVOCATION", "BROKER_ACTIVITY_UNAVAILABLE", "BROKER_OVERLOADED_MAXIMUM_TOOL_RESULT_BYTES")
        assertEquals(expected, CodexToolTerminalFailure.entries.map { it.name })
        for ((failure, code) in CodexToolTerminalFailure.entries.zip(expected)) {
            val presentation = codexToolFailurePresentation(failure)
            assertFalse(presentation.success)
            val document = Json.parseToJsonElement(presentation.content.single().text).jsonObject
            assertEquals(setOf("status", "failure"), document.keys)
            assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
            assertEquals(code, document.getValue("failure").jsonPrimitive.content)
        }
    }

    @Test
    fun `actual escaped response overflow retains byte guard and returns JSON rejection`() {
        val limit = 1024
        val result = encodeBoundedDynamicToolResult(ToolPresentation.text("bounded fixture".repeat(200), true), limit)
        assertTrue(canonicalJson(result).toByteArray(Charsets.UTF_8).size <= limit)
        assertEquals("false", result.getValue("success").jsonPrimitive.content)
        val item = result.getValue("contentItems").jsonArray.single().jsonObject
        assertEquals("inputText", item.getValue("type").jsonPrimitive.content)
        val rejection = Json.parseToJsonElement(item.getValue("text").jsonPrimitive.content).jsonObject
        assertEquals("rejected", rejection.getValue("status").jsonPrimitive.content)
        assertEquals("BROKER_OVERLOADED_MAXIMUM_TOOL_RESULT_BYTES", rejection.getValue("failure").jsonPrimitive.content)
        assertFalse(result.toString().contains("bounded fixture"))
    }

    @Test
    fun `cancellation preserves uncertain effect without claiming completion`() {
        val presentation = codexToolCancellationPresentation()
        assertFalse(presentation.success)
        val document = Json.parseToJsonElement(presentation.content.single().text).jsonObject
        assertEquals(setOf("status", "effect"), document.keys)
        assertEquals("cancelled", document.getValue("status").jsonPrimitive.content)
        assertEquals("uncertain", document.getValue("effect").jsonPrimitive.content)
    }
}
