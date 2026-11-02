package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.mcp.McpCallResult
import io.github.amichne.kast.cli.mcp.McpEmptyObject
import io.github.amichne.kast.cli.mcp.McpInitializeResult
import io.github.amichne.kast.cli.mcp.McpRequest
import io.github.amichne.kast.cli.mcp.McpResponse
import io.github.amichne.kast.cli.mcp.McpToolCall
import io.github.amichne.kast.cli.mcp.McpToolList
import io.github.amichne.kast.cli.mcp.emptyMcpArguments
import io.github.amichne.kast.cli.mcp.mcpWire
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real native transports in an owned installation, without JVM libraries or an IntelliJ host. */
@Tag("public-launcher")
class NativeTransportCompositionTest {
    @Test
    fun `native MCP initializes lists the complete catalog and rejects an unknown tool`(@TempDir evidence: Path) {
        NativePublicFixture(evidence).use { fixture ->
            val requests =
                listOf(
                    McpRequest(id = JsonPrimitive(1), method = "initialize"),
                    McpRequest(id = JsonPrimitive(2), method = "tools/list"),
                    McpRequest(
                        id = JsonPrimitive(3),
                        method = "tools/call",
                        params = mcpWire.encodeToJsonElement(McpToolCall("not-a-tool", emptyMcpArguments)),
                    ),
                )
            val output =
                nativeEntry(
                    fixture,
                    evidence,
                    "mcp",
                    listOf("--catalog=COMPLETE"),
                    requests.joinToString("\n", postfix = "\n") { mcpWire.encodeToString(it) },
                )
            val responses =
                output
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .map { mcpWire.decodeFromString<McpResponse>(it) }
                    .toList()
            assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3)), responses.map { it.id })
            val initialization = mcpWire.decodeFromJsonElement<McpInitializeResult>(checkNotNull(responses[0].result))
            assertEquals("2025-06-18", initialization.protocolVersion)
            val catalog = mcpWire.decodeFromJsonElement<McpToolList>(checkNotNull(responses[1].result))
            assertEquals(
                PublicToolIdentity.entries.map { it.toolName }.toSet() + "health_check",
                catalog.tools.map { it.name }.toSet(),
            )
            val rejection = mcpWire.decodeFromJsonElement<McpCallResult>(checkNotNull(responses[2].result))
            assertTrue(rejection.isError)
            assertNotNull(rejection.structuredContent)
            assertEquals(
                "UNKNOWN_TOOL",
                checkNotNull(rejection.structuredContent)
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun `native tool RPC plans a canonical request and rejects invalid input`(@TempDir evidence: Path) {
        NativePublicFixture(evidence).use { fixture ->
            val request =
                PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS).examples.getValue("runByName").value
            val accepted =
                nativeEntry(fixture, evidence, "rpc", listOf("--catalog=COMPLETE", "plan-query"), request.toString())
            assertEquals("planned", Json.parseToJsonElement(accepted).jsonObject.getValue("type").jsonPrimitive.content)
            val rejected =
                nativeEntry(
                    fixture,
                    evidence,
                    "rpc",
                    listOf("--catalog=COMPLETE", "plan-query"),
                    mcpWire.encodeToString(McpEmptyObject()),
                )
            assertEquals(
                "rejected",
                Json.parseToJsonElement(rejected).jsonObject.getValue("type").jsonPrimitive.content,
            )
        }
    }

    private fun nativeEntry(
        fixture: NativePublicFixture,
        evidence: Path,
        entry: String,
        arguments: List<String>,
        input: String,
    ): String {
        val output = evidence.resolve("$entry.stdout")
        val error = evidence.resolve("$entry.stderr")
        val child =
            ProcessBuilder(
                    listOf(fixture.payload.toString(), "-Duser.home=${fixture.home}", "--internal-entrypoint=$entry") +
                        arguments
                )
                .directory(fixture.home.toFile())
                .redirectOutput(output.toFile())
                .redirectError(error.toFile())
                .apply {
                    environment().clear()
                    environment()["HOME"] = fixture.home.toString()
                    environment()["PATH"] = "/usr/bin:/bin"
                }
                .start()
        child.outputStream.use { it.write(input.toByteArray()) }
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Native $entry did not settle")
            assertEquals(0, child.exitValue(), Files.readString(error))
            return Files.readString(output)
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }
}
