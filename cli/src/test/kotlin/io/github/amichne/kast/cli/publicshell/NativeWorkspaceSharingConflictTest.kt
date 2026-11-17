package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.mcp.McpCallResult
import io.github.amichne.kast.cli.mcp.McpRequest
import io.github.amichne.kast.cli.mcp.McpResponse
import io.github.amichne.kast.cli.mcp.McpToolCall
import io.github.amichne.kast.cli.mcp.mcpWire
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("public-launcher")
class NativeWorkspaceSharingConflictTest {
    @Test
    fun `CLI and MCP preserve the same explicit conflict without launching the Host`(@TempDir evidence: Path) {
        NativePublicFixture(evidence).use { fixture ->
            val saved = fixture.installation.resolve("config/environment")
            val key = "KAST_WORKSPACE_DEPENDENCY_SHARING"
            Files.writeString(saved, "$key=SHARED\n")
            NativeManagedWorkspaceFixture(fixture)
            Files.writeString(saved, "$key=PRIVATE\n")
            fixture.startSemanticCoordinator()
            val query = listOf("query", "--workspace", fixture.workspace.toString(), "--name", "OrderService", "--json")
            fixture.assertExit(fixture.start("conflict", query), 4, "conflict")
            val cli =
                Json.parseToJsonElement(Files.readString(evidence.resolve("conflict.stdout")))
                    .jsonObject
                    .getValue("document")
                    .jsonObject
            assertEquals("CONFIGURATION_CONFLICT", cli.getValue("failure").jsonPrimitive.content)
            val conflict = cli.getValue("conflict").jsonObject
            assertEquals("SHARED", conflict.getValue("recorded").jsonPrimitive.content)
            assertEquals("PRIVATE", conflict.getValue("requested").jsonPrimitive.content)
            assertEquals("SAVED_INSTALLATION", conflict.getValue("source").jsonPrimitive.content)
            assertEquals(saved.toString(), conflict.getValue("location").jsonPrimitive.content)
            assertTrue(cli.getValue("recovery").jsonPrimitive.content.contains(key))
            val mcp = callMcp(fixture)
            assertTrue(mcp.isError)
            assertEquals(cli, checkNotNull(mcp.structuredContent).getValue("document").jsonObject)
        }
    }

    private fun callMcp(fixture: NativePublicFixture): McpCallResult {
        val request =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .examples
                .getValue("runByName")
                .value
                .jsonObject
        val child =
            ProcessBuilder(
                    fixture.payload.toString(),
                    "-Duser.home=${fixture.home}",
                    "--internal-entrypoint=mcp",
                    "--catalog=COMPLETE",
                )
                .directory(fixture.workspace.toFile())
                .redirectError(fixture.evidence.resolve("mcp.stderr").toFile())
                .apply {
                    environment().clear()
                    environment()["HOME"] = fixture.home.toString()
                    environment()["PATH"] = "/usr/bin:/bin"
                }
                .start()
        try {
            child.outputStream.bufferedWriter().use { input ->
                fun send(request: McpRequest) {
                    input.write(mcpWire.encodeToString(request))
                    input.newLine()
                    input.flush()
                }
                send(McpRequest(id = JsonPrimitive(1), method = "initialize"))
                send(
                    McpRequest(
                        id = JsonPrimitive(2),
                        method = "tools/call",
                        params = mcpWire.encodeToJsonElement(McpToolCall("query_symbols", request)),
                    )
                )
                val reply =
                    CompletableFuture.supplyAsync {
                            child.inputStream.bufferedReader().use { output ->
                                output
                                    .lineSequence()
                                    .map { mcpWire.decodeFromString<McpResponse>(it) }
                                    .first { it.id == JsonPrimitive(2) }
                            }
                        }
                        .get(30, TimeUnit.SECONDS)
                return mcpWire.decodeFromJsonElement(checkNotNull(reply.result))
            }
        } finally {
            child.outputStream.close()
            if (!child.waitFor(5, TimeUnit.SECONDS)) stopNativeProcess(child)
        }
    }
}
