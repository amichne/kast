package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.boundaryExit
import io.github.amichne.kast.cli.direct.directSupportTools
import io.github.amichne.kast.cli.rpc.QueryDeliveryFixtures
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpOutputBoundaryTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `serialized output includes escaped text structured content and framing`() {
        val document = queryFixture("prefix")
        val text = "λ\"\\\u0000".repeat(50_000)
        val response = success(JsonPrimitive(17), McpCallResult(listOf(McpTextContent(text)), false, document))
        val independentlyEncoded = mcpWire.encodeToString(McpResponse.serializer(), response)
        val actual = encodeBoundedMcpResponse(response)
        assertEquals(independentlyEncoded, actual)
        assertTrue(actual.toByteArray(Charsets.UTF_8).size + 1 <= MCP_RESPONSE_LIMIT_BYTES)
        assertTrue(actual.toByteArray(Charsets.UTF_8).size > text.length)
        val result = Json.parseToJsonElement(actual).jsonObject.getValue("result").jsonObject
        assertEquals(document, result.getValue("structuredContent"))
        assertEquals(
            text,
            result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `oversized rejected evidence preserves the semantic conclusion in text and structured control`() {
        val document = queryFixture("rejected")
        val text = "λ\"\\\u0000".repeat(500_000)
        val response = success(JsonPrimitive(18), McpCallResult(listOf(McpTextContent(text)), true, document))
        assertTrue(
            mcpWire.encodeToString(McpResponse.serializer(), response).toByteArray().size > MCP_RESPONSE_LIMIT_BYTES
        )
        val actual = encodeBoundedMcpResponse(response)
        assertTrue(actual.toByteArray(Charsets.UTF_8).size + 1 <= MCP_RESPONSE_LIMIT_BYTES)
        val result = Json.parseToJsonElement(actual).jsonObject.getValue("result").jsonObject
        val structured = result.getValue("structuredContent").jsonObject
        assertEquals("OUTPUT_LIMIT", structured.getValue("type").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), result["isError"])
        assertEquals(
            structured,
            Json.parseToJsonElement(
                result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            ),
        )
        val header = structured.getValue("semantic").jsonObject
        assertEquals(
            setOf(
                "original_outcome",
                "completion",
                "authority",
                "coverage",
                "blockers",
                "omissions",
                "presentation",
                "recovery",
            ),
            header.keys,
        )
        assertEquals(
            setOf("type", "exhaustive", "known_minimum", "limitations", "progress"),
            header.getValue("coverage").jsonObject.keys,
        )
        assertEquals("rejected_document", header.getValue("original_outcome").jsonPrimitive.content)
        assertEquals("QUALIFIED", header.getValue("coverage").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(JsonPrimitive(43), header.getValue("coverage").jsonObject.getValue("known_minimum"))
        assertEquals(
            JsonPrimitive("TIME_LIMIT_REACHED"),
            header.getValue("coverage").jsonObject.getValue("limitations").jsonArray.single(),
        )
        assertEquals("READ_RESULT", header.getValue("recovery").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(McpStructuredResults.validates("query_symbols", structured))
    }

    private fun queryFixture(name: String): JsonObject =
        Json.parseToJsonElement(QueryDeliveryFixtures.serialized())
            .jsonObject
            .getValue(name)
            .jsonObject
            .getValue("document")
            .jsonObject

    @Test
    fun `argument and workspace rejections satisfy the discovered output contract`() {
        val requests =
            listOf(
                McpRequest(id = JsonPrimitive(1), method = "initialize"),
                McpRequest(id = JsonPrimitive(2), method = "tools/list"),
                McpRequest(
                    id = JsonPrimitive(3),
                    method = "tools/call",
                    params = mcpWire.encodeToJsonElement(NullArguments()),
                ),
                McpRequest(
                    id = JsonPrimitive(4),
                    method = "tools/call",
                    params = mcpWire.encodeToJsonElement(McpToolCall("health_check", emptyMcpArguments)),
                ),
            )
        val replies = exchange(requests)
        assertEquals(requests.size, replies.size)
        val schema =
            replies[1]
                .getValue("result")
                .jsonObject
                .getValue("tools")
                .jsonArray
                .single()
                .jsonObject
                .getValue("outputSchema")
                .jsonObject
        val registry =
            com.networknt.schema.SchemaRegistry.withDefaultDialect(
                com.networknt.schema.SpecificationVersion.DRAFT_2020_12
            )
        val validator = registry.getSchema(schema.toString())
        for ((reply, code) in replies.drop(2).zip(listOf("INVALID_ARGUMENTS", "NOT_GRADLE_WORKSPACE"))) {
            val result = reply.getValue("result").jsonObject
            assertEquals(JsonPrimitive(true), result["isError"])
            val document = result.getValue("structuredContent").jsonObject
            assertEquals(code, document.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertTrue(validator.validate(document.toString(), com.networknt.schema.InputFormat.JSON).isEmpty())
            assertFalse(McpStructuredResults.validatesSemantic("health_check", document))
        }
    }

    @Test
    fun `modern health retains the original boundary rejection and a schema admitted document`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val input =
            McpRequest(
                id = JsonPrimitive(1),
                method = "tools/call",
                params = mcpWire.encodeToJsonElement(ModernHealth()),
            )
        val output = ByteArrayOutputStream()
        KastMcpServer(
                catalog = directSupportTools(),
                invoke = { _, _ -> boundaryExit(CliBoundaryExitStatus.RUNTIME, "installation-shutdown") },
                root = { FilesystemCanonicalRootDiscovery.discover(temporary) },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(
                BufferedInputStream(
                    ByteArrayInputStream((mcpWire.encodeToString(McpRequest.serializer(), input) + "\n").toByteArray())
                ),
                PrintStream(output),
            )
        val result =
            Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject.getValue("result").jsonObject
        assertEquals(JsonPrimitive(true), result["isError"])
        assertTrue(result.getValue("content").toString().contains("installation-shutdown"))
        val structured = result.getValue("structuredContent").jsonObject
        assertEquals(
            "installation-shutdown",
            structured.getValue("document").jsonObject.getValue("reason").jsonPrimitive.content,
        )
        assertTrue(McpStructuredResults.validates("health_check", structured))
        assertFalse(McpStructuredResults.validatesSemantic("health_check", structured))
    }

    @Test
    fun `schema admitted rejection cannot be published as a successful exit`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val document = CanonicalJsonDocument.generated(HealthRejection.serializer()).create(HealthRejection())
        assertTrue(
            McpStructuredResults.validatesSemantic("health_check", Json.parseToJsonElement(document.value).jsonObject)
        )
        val output = ByteArrayOutputStream()
        val diagnostic = ByteArrayOutputStream()
        val request =
            McpRequest(
                id = JsonPrimitive(1),
                method = "tools/call",
                params = mcpWire.encodeToJsonElement(ModernHealth()),
            )
        KastMcpServer(
                catalog = directSupportTools(),
                invoke = { _, _ -> CliExit.Complete(document) },
                root = { FilesystemCanonicalRootDiscovery.discover(temporary) },
                diagnostic = PrintStream(diagnostic),
            )
            .run(
                BufferedInputStream(
                    ByteArrayInputStream(
                        (mcpWire.encodeToString(McpRequest.serializer(), request) + "\n").toByteArray()
                    )
                ),
                PrintStream(output),
            )
        val result =
            Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject.getValue("result").jsonObject
        assertEquals(JsonPrimitive(true), result["isError"])
        assertEquals(
            "INVALID_RESULT_SCHEMA",
            result
                .getValue("structuredContent")
                .jsonObject
                .getValue("error")
                .jsonObject
                .getValue("code")
                .jsonPrimitive
                .content,
        )
        assertTrue(diagnostic.toString(Charsets.UTF_8).contains("\"schemaFailure\":\"OUTCOME_MISMATCH\""))
        assertFalse(diagnostic.toString(Charsets.UTF_8).contains("\"outcome\":\"COMPLETED\""))
    }

    private fun exchange(requests: List<McpRequest>): List<JsonObject> {
        val output = ByteArrayOutputStream()
        val input = requests.joinToString("\n", postfix = "\n") { mcpWire.encodeToString(McpRequest.serializer(), it) }
        KastMcpServer(
                catalog = directSupportTools(),
                invoke = { _, _ -> error("rejection must precede invocation") },
                root = { CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND) },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(BufferedInputStream(ByteArrayInputStream(input.toByteArray())), PrintStream(output))
        return output
            .toString(Charsets.UTF_8)
            .lineSequence()
            .filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }
            .toList()
    }

    // Deliberately invalid arguments prove null is rejected rather than normalized to an empty object.
    @Serializable
    private data class NullArguments(val name: String = "health_check", @Required val arguments: JsonElement = JsonNull)

    @Serializable
    private data class ModernHealth(
        val name: String = "health_check",
        val arguments: McpEmptyObject = McpEmptyObject(),
        @kotlinx.serialization.SerialName("_meta")
        val meta: McpRequestMeta = McpRequestMeta(MODERN_PROTOCOL_VERSION, emptyMcpArguments),
    )

    @Serializable
    private data class HealthRejection(
        @Required val status: HealthStatus = HealthStatus.REJECTED,
        val error: HealthError = HealthError(),
    )

    @Serializable
    private enum class HealthStatus {
        @kotlinx.serialization.SerialName("rejected") REJECTED
    }

    @Serializable
    private data class HealthError(
        val code: HealthFailure = HealthFailure.HOST_UNAVAILABLE,
        val message: String = "fixture unavailable",
    )

    @Serializable
    private enum class HealthFailure {
        HOST_UNAVAILABLE
    }
}
