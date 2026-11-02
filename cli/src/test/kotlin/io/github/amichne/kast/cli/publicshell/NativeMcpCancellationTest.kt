package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.mcp.McpRequest
import io.github.amichne.kast.cli.mcp.McpResponse
import io.github.amichne.kast.cli.mcp.McpToolCall
import io.github.amichne.kast.cli.mcp.mcpWire
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("public-launcher")
class NativeMcpCancellationTest {
    @Test
    fun `native MCP cancellation closes the exact hosted socket and suppresses its response`(@TempDir evidence: Path) =
        assertCancellation(evidence, NativeMcpCancellation.NOTIFICATION)

    @Test
    fun `native MCP input EOF closes the exact hosted socket and drains its worker`(@TempDir evidence: Path) =
        assertCancellation(evidence, NativeMcpCancellation.END_OF_INPUT)

    private fun startMcp(fixture: NativePublicFixture, evidence: Path): Process =
        ProcessBuilder(
                fixture.payload.toString(),
                "-Duser.home=${fixture.home}",
                "--internal-entrypoint=mcp",
                "--catalog=COMPLETE",
            )
            .directory(fixture.workspace.toFile())
            .redirectOutput(evidence.resolve("mcp.stdout").toFile())
            .redirectError(evidence.resolve("mcp.stderr").toFile())
            .apply {
                environment().clear()
                environment()["HOME"] = fixture.home.toString()
                environment()["PATH"] = "/usr/bin:/bin"
            }
            .start()

    private fun queryRequest(): McpRequest {
        val request =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .examples
                .getValue("runByName")
                .value
                .jsonObject
        return McpRequest(
            id = JsonPrimitive(2),
            method = "tools/call",
            params = mcpWire.encodeToJsonElement(McpToolCall("query_symbols", request)),
        )
    }

    private fun assertCancellation(evidence: Path, trigger: NativeMcpCancellation) {
        NativePublicFixture(evidence).use { fixture ->
            NativePublicPeer(fixture, NativePeerReply.HOLD_QUERY).use { peer ->
                assertConnectedCancellation(fixture, peer, evidence, trigger)
            }
        }
    }

    private fun assertConnectedCancellation(
        fixture: NativePublicFixture,
        peer: NativePublicPeer,
        evidence: Path,
        trigger: NativeMcpCancellation,
    ) {
        val child = startMcp(fixture, evidence)
        try {
            child.outputStream.use { input ->
                input.send(McpRequest(id = JsonPrimitive(1), method = "initialize"))
                input.send(queryRequest())
                peer.awaitQuery()
                if (trigger == NativeMcpCancellation.NOTIFICATION) {
                    input.send(
                        McpRequest(
                            method = "notifications/cancelled",
                            params = mcpWire.encodeToJsonElement(NativeCancellationParams(JsonPrimitive(2))),
                        )
                    )
                    input.send(McpRequest(id = JsonPrimitive(3), method = "ping"))
                }
            }
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Native MCP did not settle")
            assertEquals(0, child.exitValue(), Files.readString(evidence.resolve("mcp.stderr")))
            peer.assertConsumed()
            val ids =
                Files.readAllLines(evidence.resolve("mcp.stdout")).filter(String::isNotBlank).map {
                    mcpWire.decodeFromString<McpResponse>(it).id
                }
            assertEquals(
                if (trigger == NativeMcpCancellation.NOTIFICATION) listOf(JsonPrimitive(1), JsonPrimitive(3))
                else listOf(JsonPrimitive(1)),
                ids,
            )
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                child.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}

private fun OutputStream.send(request: McpRequest) {
    write((mcpWire.encodeToString(request) + "\n").toByteArray())
    flush()
}

@Serializable private data class NativeCancellationParams(val requestId: JsonPrimitive)

private enum class NativeMcpCancellation {
    NOTIFICATION,
    END_OF_INPUT,
}
