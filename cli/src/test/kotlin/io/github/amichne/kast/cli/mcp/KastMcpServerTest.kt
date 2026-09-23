package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CanonicalRootDiscovery
import io.github.amichne.kast.cli.CanonicalRootFailure
import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.CliTextDocumentAdmission
import io.github.amichne.kast.cli.InstalledHostedToolDocument
import io.github.amichne.kast.cli.InstalledServerExecutionBudgetDocument
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KastMcpServerTest {
    @Test
    fun `handshake lists the admitted catalog and rejects a call outside Gradle`() {
        var calls = 0
        val diagnostics = ByteArrayOutputStream()
        val server = KastMcpServer(
            catalog = listOf(
                InstalledHostedToolDocument(
                    operationId = "query.run",
                    name = "search_classes",
                    description = "Search classes",
                    deferLoading = false,
                    effect = "read",
                    approvalPolicy = "none",
                    executionBudget = InstalledServerExecutionBudgetDocument(1000, 1000),
                    inputSchema = Json.encodeToJsonElement(TestSchema("object")),
                    outputSchema = Json.encodeToJsonElement(TestSchema("object")),
                ),
            ),
            invoke = { _, _ -> calls++; error("must not invoke") },
            root = { CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND) },
            diagnostic = PrintStream(diagnostics),
        )
        val input = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_classes","arguments":{}}}
        """.trimIndent() + "\n"
        val output = ByteArrayOutputStream()
        server.run(BufferedInputStream(ByteArrayInputStream(input.toByteArray())), PrintStream(output))
        val replies = output.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }.toList()
        assertEquals(listOf("1", "2", "3"), replies.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("search_classes", replies[1].getValue("result").jsonObject.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(replies[2].getValue("result").jsonObject.getValue("isError").jsonPrimitive.content.toBoolean())
        assertEquals(0, calls)
        assertTrue(diagnostics.toString(Charsets.UTF_8).contains("\"outcome\":\"REJECTED\""))
    }

    @Test
    fun `admitted call runs one CLI operation and records completion`() {
        val diagnostics = ByteArrayOutputStream()
        val output = ByteArrayOutputStream()
        var calls = 0
        val document = (CliTextDocument.admit("complete") as CliTextDocumentAdmission.Admitted).document
        KastMcpServer(
            catalog = listOf(
                InstalledHostedToolDocument(
                    "query.run", "search_classes", "Search classes", false, "read", "none",
                    InstalledServerExecutionBudgetDocument(1000, 1000),
                    Json.encodeToJsonElement(TestSchema("object")), Json.encodeToJsonElement(TestSchema("object")),
                ),
            ),
            invoke = { _, _ -> calls++; CliExit.Complete(document) },
            root = { CanonicalRootDiscovery.Discovered(CanonicalRoot(java.nio.file.Path.of("/tmp"))) },
            diagnostic = PrintStream(diagnostics),
        ).run(
            BufferedInputStream(ByteArrayInputStream(
                (Json { encodeDefaults = true }.encodeToString(TestCallRequest(params = TestCallParams("search_classes", TestEmptyArguments()))) + "\n")
                    .toByteArray(),
            )),
            PrintStream(output),
        )
        assertEquals(1, calls)
        assertTrue(output.toString(Charsets.UTF_8).contains("\"isError\":false"))
        assertTrue(diagnostics.toString(Charsets.UTF_8).contains("\"outcome\":\"COMPLETED\""))
    }

    @Test
    fun `workspace open is exposed on demand and rejects arguments`() {
        var opens = 0
        val document = (CliTextDocument.admit("opened") as CliTextDocumentAdmission.Admitted).document
        val server = KastMcpServer(
            catalog = emptyList(),
            invoke = { _, _ -> error("semantic tool must not run") },
            root = { CanonicalRootDiscovery.Discovered(CanonicalRoot(java.nio.file.Path.of("/tmp"))) },
            openWorkspace = { opens++; CliExit.Complete(document) },
        )
        val input = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list"}
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"workspace_open","arguments":{"root":"/other"}}}
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"workspace_open","arguments":{}}}
        """.trimIndent() + "\n"
        val output = ByteArrayOutputStream()
        server.run(BufferedInputStream(ByteArrayInputStream(input.toByteArray())), PrintStream(output))
        val replies = output.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }.toList()
        assertEquals("workspace_open", replies[0].getValue("result").jsonObject.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(replies[1].getValue("result").jsonObject.getValue("isError").jsonPrimitive.content.toBoolean())
        assertEquals("false", replies[2].getValue("result").jsonObject.getValue("isError").jsonPrimitive.content)
        assertEquals(1, opens)
    }
}

@Serializable
private data class TestSchema(val type: String)

@Serializable
private data class TestCallRequest(
    val jsonrpc: String = "2.0",
    val id: Int = 1,
    val method: String = "tools/call",
    val params: TestCallParams,
)

@Serializable
private data class TestCallParams(val name: String, val arguments: TestEmptyArguments)

@Serializable
private class TestEmptyArguments
