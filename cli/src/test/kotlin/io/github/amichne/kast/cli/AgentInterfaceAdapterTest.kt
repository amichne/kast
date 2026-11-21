package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.direct.directToolDocument
import io.github.amichne.kast.cli.mcp.KastMcpServer
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.AgentInterfaceDiscoveryFixture
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AgentInterfaceAdapterTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `MCP emits the admitted production documents without extra invocation`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        for (outcome in outcomes()) {
            for (detail in ToolOutputDetail.entries) {
                assertAdapters(outcome, detail)
            }
        }
    }

    private val catalog =
        listOf(installedHostedBootstrap().tools.single { it.name == "query_symbols" }.directToolDocument())

    private fun assertAdapters(outcome: ProjectedOperationOutcome, detail: ToolOutputDetail) {
        val arguments =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .examples
                .getValue("runByName")
                .value
                .jsonObject
        val exit = outcome.exit(detail)
        val expected = Json.parseToJsonElement(exit.document.value).jsonObject
        assertTrue(McpStructuredResults.validates("query_symbols", expected))
        val mcp = assertMcp(exit, arguments)
        println(
            adapterJson.encodeToString(
                AdapterBytes(
                    expected.getValue("status").jsonPrimitive.content,
                    detail,
                    exit.document.value.toByteArray(Charsets.UTF_8).size,
                    mcp.toByteArray(Charsets.UTF_8).size,
                )
            )
        )
    }

    private fun assertMcp(exit: CliExit, arguments: JsonObject): String {
        var calls = 0
        val input =
            adapterJson.encodeToString(AdapterInitialize()) +
                "\n" +
                adapterJson.encodeToString(AdapterCall(params = AdapterCallParameters(arguments = arguments))) +
                "\n"
        val writer = PipedOutputStream()
        val source = PipedInputStream(writer, input.toByteArray(Charsets.UTF_8).size + 1)
        val output =
            object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    super.write(bytes, offset, length)
                    // A real MCP caller keeps input open until the response; EOF would cancel the default RUN.
                    if (toString(Charsets.UTF_8).count { it == '\n' } == 2) writer.close()
                }
            }
        writer.write(input.toByteArray(Charsets.UTF_8))
        writer.flush()
        KastMcpServer(
                catalog,
                { name, supplied ->
                    calls++
                    assertEquals(1, calls, "No semantic retry or detail read is permitted")
                    assertEquals("query_symbols", name)
                    assertEquals(arguments, supplied)
                    exit
                },
                { FilesystemCanonicalRootDiscovery.discover(temporary) },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(BufferedInputStream(source), PrintStream(output))
        source.close()
        writer.close()
        assertEquals(1, calls)
        val lines = output.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
        assertEquals(2, lines.size)
        val result = Json.parseToJsonElement(lines.last()).jsonObject.getValue("result").jsonObject
        assertEquals(Json.parseToJsonElement(exit.document.value), result.getValue("structuredContent"))
        val content = result.getValue("content").jsonArray
        assertEquals(1, content.size)
        assertEquals(exit.document.value, content.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(
            exit is CliExit.OperationRejected,
            result.getValue("isError").jsonPrimitive.content.toBooleanStrict(),
        )
        return lines.last()
    }

    private fun outcomes(): List<ProjectedOperationOutcome> =
        listOf(
            AgentInterfaceDiscoveryFixture.complete(),
            AgentInterfaceDiscoveryFixture.qualified(),
            AgentInterfaceDiscoveryFixture.complete(evidenceOnly = true),
            AgentInterfaceDiscoveryFixture.rejected(),
        )
}

private fun ProjectedOperationOutcome.exit(detail: ToolOutputDetail): CliExit =
    when (this) {
        is ProjectedOperationOutcome.Complete -> CliExit.Complete(document.present(detail))
        is ProjectedOperationOutcome.Qualified -> CliExit.Qualified(document.present(detail))
        is ProjectedOperationOutcome.Rejected -> CliExit.OperationRejected(document.present(detail))
    }

private val adapterJson = Json { encodeDefaults = true }

@Serializable
private data class AdapterInitialize(val jsonrpc: String = "2.0", val id: Int = 0, val method: String = "initialize")

@Serializable
private data class AdapterCall(
    val params: AdapterCallParameters,
    val jsonrpc: String = "2.0",
    val id: Int = 1,
    val method: String = "tools/call",
)

// The public tool owns this dynamic argument document and admits it before invocation.
@Serializable private data class AdapterCallParameters(val arguments: JsonObject, val name: String = "query_symbols")

@Serializable
private data class AdapterBytes(
    val status: String,
    val detail: ToolOutputDetail,
    val documentBytes: Int,
    val mcpResponseBytes: Int,
)
