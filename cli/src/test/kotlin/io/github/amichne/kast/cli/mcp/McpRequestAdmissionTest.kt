package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class McpRequestAdmissionTest {
    @Test
    fun `unquoted primitive token is a parse failure while valid nonobject JSON is an invalid request`() {
        val malformed = assertInstanceOf(McpRequestAdmission.Rejected::class.java, admitMcpRequest("not-json"))
        assertEquals(-32700, malformed.error.code)
        assertEquals(JsonNull, malformed.id)
        for (frame in listOf(Json.encodeToString(true), Json.encodeToString(emptyList<Int>()))) {
            val invalid = assertInstanceOf(McpRequestAdmission.Rejected::class.java, admitMcpRequest(frame))
            assertEquals(-32600, invalid.error.code)
            assertEquals(JsonNull, invalid.id)
        }
    }

    @Test
    fun `malformed JSON produces a parse error with an explicit null identifier`() {
        val replies = replies("{")
        assertEquals(1, replies.size)
        assertError(replies.single(), -32700, JsonNull)
    }

    @Test
    fun `invalid envelopes preserve an independently admitted identifier`() {
        val inputs =
            listOf(
                AdmissionEnvelope(jsonrpc = null, id = JsonPrimitive(2)),
                AdmissionEnvelope(jsonrpc = "1.0", id = JsonPrimitive(2)),
                AdmissionEnvelope(id = JsonPrimitive(2), method = JsonPrimitive(3)),
                AdmissionEnvelope(id = JsonPrimitive(2), params = JsonPrimitive(true)),
                AdmissionEnvelope(id = JsonPrimitive(2), params = JsonNull),
            )
        for (input in inputs) {
            val replies = replies(admissionFixtureJson.encodeToString(input))
            assertEquals(1, replies.size, input.toString())
            assertError(replies.single(), -32600, JsonPrimitive(2))
        }
    }

    @Test
    fun `invalid identifier shapes produce an invalid request with a null identifier`() {
        val identifiers =
            listOf(
                JsonPrimitive(true),
                JsonPrimitive(false),
                Json.encodeToJsonElement(AdmissionEmptyObject()),
                Json.encodeToJsonElement(emptyList<Int>()),
            )
        for (identifier in identifiers) {
            val replies = replies(admissionFixtureJson.encodeToString(AdmissionEnvelope(id = identifier)))
            assertEquals(1, replies.size, identifier.toString())
            assertError(replies.single(), -32600, JsonNull)
        }
    }

    @Test
    fun `invalid envelope without an identifier is rejected rather than treated as a notification`() {
        val replies = replies(admissionFixtureJson.encodeToString(AdmissionEnvelope(method = null)))
        assertEquals(1, replies.size)
        assertError(replies.single(), -32600, JsonNull)
    }

    @Test
    fun `valid notifications stay silent while explicit null and string identifiers receive responses`() {
        val replies =
            replies(
                admissionFixtureJson.encodeToString(
                    AdmissionEnvelope(id = JsonPrimitive(1), method = JsonPrimitive("initialize"))
                ),
                admissionFixtureJson.encodeToString(AdmissionEnvelope()),
                admissionFixtureJson.encodeToString(AdmissionEnvelope(id = JsonNull)),
                admissionFixtureJson.encodeToString(AdmissionEnvelope(id = JsonPrimitive("request-2"))),
            )
        assertEquals(3, replies.size)
        assertEquals(JsonNull, replies[1].getValue("id"))
        assertEquals(JsonPrimitive("request-2"), replies[2].getValue("id"))
        assertEquals(true, "result" in replies[1])
        assertEquals(false, "error" in replies[1])
    }

    private fun assertError(reply: JsonObject, expectedCode: Int, expectedId: JsonElement) {
        assertEquals(JsonPrimitive("2.0"), reply.getValue("jsonrpc"))
        assertEquals(expectedId, reply.getValue("id"))
        assertEquals(expectedCode, reply.getValue("error").jsonObject.getValue("code").jsonPrimitive.content.toInt())
        assertEquals(false, "result" in reply)
    }

    private fun replies(vararg frames: String): List<JsonObject> {
        val output = ByteArrayOutputStream()
        KastMcpServer(
                catalog = emptyList(),
                invoke = { _, _ -> error("request admission cannot invoke a tool") },
                root = { CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND) },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(
                BufferedInputStream(ByteArrayInputStream(frames.joinToString("\n", postfix = "\n").toByteArray())),
                PrintStream(output),
            )
        return output
            .toString(Charsets.UTF_8)
            .lineSequence()
            .filter(String::isNotBlank)
            .map {
                Json.parseToJsonElement(it).jsonObject
            }
            .toList()
    }
}

private val admissionFixtureJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** Omitted fields and incompatible property types deliberately exercise JSON-RPC ingress rejection. */
@Serializable
private data class AdmissionEnvelope(
    val jsonrpc: String? = "2.0",
    val id: JsonElement? = null,
    val method: JsonElement? = JsonPrimitive("ping"),
    val params: JsonElement? = null,
)

@Serializable private class AdmissionEmptyObject
