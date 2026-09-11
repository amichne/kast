package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeToolIdentityTest {
    @Test
    fun `started call exposes raw arguments in a native expandable tool item`() {
        val item = Json.parseToJsonElement("""{"type":"dynamicToolCall","id":"call-1","namespace":"kast","tool":"query","arguments":{"selector":"exact:v2:opaque"},"status":"inProgress","futureField":"retained"}""").jsonObject
        val projected = (CodexToolCallProjector.projectStarted(item) as CodexToolCallProjection.Projected).item
        assertEquals("mcpToolCall", projected.getValue("type").jsonPrimitive.content)
        assertEquals("kast", projected.getValue("server").jsonPrimitive.content)
        assertRetained(item, projected)
        assertEquals(JsonNull, projected["result"])
        assertEquals(JsonNull, projected["error"])
    }

    @Test
    fun `completed and failed calls expose every raw result without summary parsing`() {
        for (success in listOf(true, false)) {
            val status = if (success) "completed" else "failed"
            val item = Json.parseToJsonElement("""{"type":"dynamicToolCall","id":"call-1","namespace":"kast","tool":"query","arguments":{},"status":"$status","success":$success,"durationMs":17,"contentItems":[{"type":"inputText","text":"query: qualified"},{"type":"inputText","text":"{\"status\":\"qualified\",\"items\":[],\"failures\":[\"budget\"]}"}]}""").jsonObject
            val projected = (CodexToolCallProjector.projectCompleted(item) as CodexToolCallProjection.Projected).item
            assertEquals("mcpToolCall", projected.getValue("type").jsonPrimitive.content)
            assertRetained(item, projected)
            val content = projected.getValue("result").jsonObject.getValue("content").jsonArray
            assertEquals(listOf("text", "text"), content.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            assertEquals(item.getValue("contentItems").jsonArray.map { it.jsonObject["text"] }, content.map { it.jsonObject["text"] })
            if (success) assertEquals(JsonNull, projected["error"])
            else assertEquals("Tool call failed", projected.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        }
    }

    @Test
    fun `contradictory completion is rejected before display`() {
        val item = Json.parseToJsonElement("""{"type":"dynamicToolCall","id":"call-1","namespace":"kast","tool":"query","arguments":{},"status":"failed","success":true}""").jsonObject
        assertEquals(CodexToolCallProjection.Rejected(CodexToolCallProjectionFailure.COMPLETION_SUCCESS_CONFLICT), CodexToolCallProjector.projectCompleted(item))
    }

    private fun assertRetained(original: JsonObject, projected: JsonObject) {
        original.filterKeys { it != "type" }.forEach { (key, value) -> assertEquals(value, projected[key], key) }
    }
}

/** Checks retained upstream evidence and the native client's raw, expandable content surface. */
internal fun assertRawToolDisplay(original: JsonObject, displayed: JsonObject) {
    assertEquals("mcpToolCall", displayed.getValue("type").jsonPrimitive.content)
    assertEquals(original["namespace"], displayed["server"])
    original.filterKeys { it != "type" }.forEach { (key, value) -> assertEquals(value, displayed[key], key) }
    if (original["status"] == JsonPrimitive("inProgress")) {
        assertEquals(JsonNull, displayed["result"])
        assertEquals(JsonNull, displayed["error"])
    } else {
        val raw = (original["contentItems"] as? JsonArray).orEmpty()
        val visible = displayed.getValue("result").jsonObject.getValue("content").jsonArray
        assertEquals(raw.size, visible.size)
        raw.zip(visible).forEach { (source, rendered) ->
            assertEquals("text", rendered.jsonObject.getValue("type").jsonPrimitive.content)
            val expected = if (source.jsonObject["type"] == JsonPrimitive("inputText")) source.jsonObject.getValue("text") else JsonPrimitive(source.toString())
            assertEquals(expected, rendered.jsonObject["text"])
        }
        if (original["status"] == JsonPrimitive("failed")) {
            assertEquals("Tool call failed", displayed.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        } else assertEquals(JsonNull, displayed["error"])
    }
}
