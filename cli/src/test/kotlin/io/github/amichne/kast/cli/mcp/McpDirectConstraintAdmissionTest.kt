package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.appserver.query.PublicToolParameter
import io.github.amichne.kast.appserver.query.PublicToolRule
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.direct.DirectToolDocument
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpDirectConstraintAdmissionTest {
    @Test
    fun `direct invocation and MCP preserve runner work conflict without semantic effects`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle.kts"), "")
        for (mode in listOf(null, QueryExecutionModeDocument.RUNNER_CONTROLLED)) {
            val semanticCalls = AtomicInteger()
            val tool =
                DirectToolDocument(
                    "query_symbols",
                    "Query symbols",
                    PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS),
                    McpStructuredResults.schemaFor("query_symbols"),
                    OperationEffect.INTELLIJ_READ,
                )
            val session =
                KastDirectToolSession(
                    catalog = listOf(tool),
                    root = { FilesystemCanonicalRootDiscovery.discover(root) },
                    start = { Refinement.Refined(Unit) },
                    invokePublic = {
                        semanticCalls.incrementAndGet()
                        error("Rejected input cannot invoke semantics")
                    },
                )
            val arguments = mcpWire.encodeToJsonElement(WorkConflictArguments(WorkConflictRun(mode))).jsonObject
            val exit = session.invoke("query_symbols", arguments)
            assertEquals(2, exit.code)
            assertEquals(
                PublicToolInputFailure.Constraint(
                    PublicToolParameter.EXECUTION_MODE,
                    setOf(PublicToolParameter.EXECUTION_BUDGET_MAX_WORK_UNITS),
                    PublicToolRule.RUNNER_CONTROLLED_WORK_LIMIT,
                ),
                assertInstanceOf(CliExit.PublicInputRejected::class.java, exit).failure,
            )
            assertConstraint(mcpWire.parseToJsonElement(exit.document.value).jsonObject)
            for (modern in listOf(false, true)) {
                assertMcpConstraint(exchange(session, arguments, modern))
            }
            assertEquals(0, semanticCalls.get())
        }
    }

    private fun assertMcpConstraint(result: JsonObject) {
        assertEquals(JsonPrimitive(true), result.getValue("isError"))
        val structured = result.getValue("structuredContent").jsonObject
        assertEquals("BOUNDARY_REJECTED", structured.getValue("type").jsonPrimitive.content)
        assertConstraint(structured.getValue("document").jsonObject)
        assertTrue(McpStructuredResults.validates("query_symbols", structured))
        assertFalse(McpStructuredResults.validatesSemantic("query_symbols", structured))
        assertEquals(
            structured,
            mcpWire.parseToJsonElement(
                result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            ),
        )
    }

    private fun exchange(session: KastDirectToolSession, arguments: JsonObject, modern: Boolean): JsonObject {
        val output = ByteArrayOutputStream()
        val published = CountDownLatch(if (modern) 1 else 2)
        val input = PipedInputStream()
        val writer = PipedOutputStream(input)
        val executor = Executors.newSingleThreadExecutor()
        val server =
            KastMcpServer(
                session.catalog,
                session::invoke,
                session.root,
                session.start,
                PrintStream(ByteArrayOutputStream()),
            )
        val running = executor.submit {
            server.run(
                BufferedInputStream(input),
                object : PrintStream(output) {
                    override fun println(line: String?) {
                        super.println(line)
                        published.countDown()
                    }
                },
            )
        }
        try {
            if (!modern) writer.send(McpRequest(id = JsonPrimitive(0), method = "initialize"))
            val call =
                WorkConflictCall(
                    arguments,
                    if (modern) McpRequestMeta(MODERN_PROTOCOL_VERSION, emptyMcpArguments) else null,
                )
            val request =
                McpRequest(id = JsonPrimitive(1), method = "tools/call", params = mcpWire.encodeToJsonElement(call))
            writer.send(request)
            assertTrue(published.await(5, TimeUnit.SECONDS), "MCP must publish the actionable rejection before EOF")
            writer.close()
            running.get(5, TimeUnit.SECONDS)
            return mcpWire
                .parseToJsonElement(output.toString(Charsets.UTF_8).trim().lineSequence().last())
                .jsonObject
                .getValue("result")
                .jsonObject
        } finally {
            writer.close()
            input.close()
            executor.shutdownNow()
        }
    }

    private fun PipedOutputStream.send(request: McpRequest) {
        write((mcpWire.encodeToString(McpRequest.serializer(), request) + "\n").toByteArray())
        flush()
    }

    private fun assertConstraint(document: JsonObject) {
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals("usage", document.getValue("boundary").jsonPrimitive.content)
        val detail = document.getValue("detail").jsonObject
        assertEquals(setOf("type", "parameters", "rule", "remedies"), detail.keys)
        assertEquals("REQUEST_CONSTRAINT_REJECTED", detail.getValue("type").jsonPrimitive.content)
        assertEquals(
            listOf("executionMode", "executionBudget.maxWorkUnits"),
            detail.getValue("parameters").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("RUNNER_CONTROLLED_WORK_LIMIT", detail.getValue("rule").jsonPrimitive.content)
        assertEquals(
            listOf("REMOVE_MAX_WORK_UNITS", "SELECT_BOUNDED"),
            detail.getValue("remedies").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}

@Serializable
private data class WorkConflictCall(
    val arguments: JsonObject,
    @SerialName("_meta") val meta: McpRequestMeta?,
    @Required val name: String = "query_symbols",
)

@Serializable private data class WorkConflictArguments(val request: WorkConflictRun)

@Serializable
private data class WorkConflictRun(
    val executionMode: QueryExecutionModeDocument?,
    @Required val type: String = "RUN",
    @Required val source: WorkConflictSource = WorkConflictSource(),
    @Required val terminalStep: WorkConflictTerminal = WorkConflictTerminal(),
    @Required val executionBudget: WorkConflictBudget = WorkConflictBudget(),
)

@Serializable private data class WorkConflictSource(@Required val type: String = "ALL_DECLARATIONS")

@Serializable private data class WorkConflictTerminal(@Required val type: String = "EMIT")

@Serializable private data class WorkConflictBudget(@Required val maxWorkUnits: Long = 1)
