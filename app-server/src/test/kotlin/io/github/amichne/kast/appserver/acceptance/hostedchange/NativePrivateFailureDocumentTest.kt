package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativePrivateFailureDocumentTest {
    @Test
    fun `private diagnostic encodes bounded stage evidence with explicit fields`() {
        val failure = IllegalStateException("bounded")
        failure.stackTrace = arrayOf(StackTraceElement("sample.Owner", "invoke", "Owner.kt", 42))

        val encoded =
            Json.parseToJsonElement(Json.encodeToString(NativePrivateFailureDocument.from(failure))).jsonObject
        assertEquals(setOf("class", "symbol", "frames"), encoded.keys)
        assertEquals("java.lang.IllegalStateException", encoded.getValue("class").jsonPrimitive.content)
        assertEquals("bounded", encoded.getValue("symbol").jsonPrimitive.content)
        val frame = encoded.getValue("frames").jsonArray.single().jsonObject
        assertEquals(setOf("class", "method", "line"), frame.keys)
        assertEquals("sample.Owner", frame.getValue("class").jsonPrimitive.content)
        assertEquals("invoke", frame.getValue("method").jsonPrimitive.content)
        assertEquals(42, frame.getValue("line").jsonPrimitive.content.toInt())
    }

    @Test
    fun `private diagnostic with unsafe message retains explicit null symbol`() {
        val failure = IllegalStateException("source\npayload")
        failure.stackTrace = emptyArray()
        val encoded =
            Json.parseToJsonElement(Json.encodeToString(NativePrivateFailureDocument.from(failure))).jsonObject
        assertEquals(JsonNull, encoded.getValue("symbol"))
        assertEquals(0, encoded.getValue("frames").jsonArray.size)
    }
}
