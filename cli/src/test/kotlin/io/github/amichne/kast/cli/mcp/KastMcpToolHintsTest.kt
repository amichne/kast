package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.cli.InstalledHostedToolDocument
import io.github.amichne.kast.cli.installedHostedBootstrap
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastMcpToolHintsTest {
    @Test
    fun `semantic reads advertise safe MCP hints while change retains write hints`() {
        val readNames =
            setOf(
                "check_diagnostics",
                "query_symbols",
                "read_relations",
                "source_read",
                "symbol_inspect",
                "symbol_lookup",
                "traverse_relations",
            )
        val installed = installedHostedBootstrap().tools
        val reads = installed.filter { it.name in readNames }
        assertEquals(readNames, reads.mapTo(linkedSetOf()) { it.name })
        val change = installed.single { it.name == "change" }
        val tools = listedTools(reads, change)
        assertEquals(
            readNames + "change",
            tools.mapTo(linkedSetOf()) { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        for (tool in tools) {
            val document = tool.jsonObject
            val read = document.getValue("name").jsonPrimitive.content in readNames
            val hints = document.getValue("annotations").jsonObject
            assertEquals(read.toString(), hints.getValue("readOnlyHint").jsonPrimitive.content)
            assertEquals((!read).toString(), hints.getValue("destructiveHint").jsonPrimitive.content)
            assertEquals(read.toString(), hints.getValue("idempotentHint").jsonPrimitive.content)
            assertEquals("false", hints.getValue("openWorldHint").jsonPrimitive.content)
        }
    }

    private fun listedTools(reads: List<InstalledHostedToolDocument>, change: InstalledHostedToolDocument): JsonArray {
        val output = ByteArrayOutputStream()
        KastMcpServer(
                catalog = reads,
                invoke = { _, _ -> error("no invocation expected") },
                root = { CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND) },
                supplemental =
                    listOf(
                        McpSupplementalTool(
                            name = "change",
                            description = change.description,
                            inputSchema = change.inputSchema,
                            readOnly = false,
                            invoke = { error("no invocation expected") },
                        )
                    ),
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(
                BufferedInputStream(
                    ByteArrayInputStream(
                        """
                        {"jsonrpc":"2.0","id":1,"method":"initialize"}
                           {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                        """
                            .trimIndent()
                            .plus("\n")
                            .toByteArray()
                    )
                ),
                PrintStream(output),
            )
        return output.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).last().let {
            Json.parseToJsonElement(it).jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
        }
    }
}
