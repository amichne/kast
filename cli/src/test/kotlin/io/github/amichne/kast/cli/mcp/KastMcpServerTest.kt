package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.CliTextDocumentAdmission
import io.github.amichne.kast.cli.InstalledHostedToolDocument
import io.github.amichne.kast.cli.InstalledServerExecutionBudgetDocument
import io.github.amichne.kast.cli.installedHostedBootstrap
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("LargeClass")
class KastMcpServerTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `semantic reads advertise safe MCP hints while change retains write hints`() {
        val readNames =
            setOf(
                "check_diagnostics",
                "query_symbols",
                "read_relations",
                "search_classes",
                "search_declarations",
                "search_functions",
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

    private fun admittedRoot(): CanonicalRootDiscovery {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        return FilesystemCanonicalRootDiscovery.discover(temporary)
    }

    @Test
    fun `initialize starts preparation for the bound Gradle root once`() {
        val selected = (admittedRoot() as CanonicalRootDiscovery.Discovered).root
        var starts = 0
        val output = ByteArrayOutputStream()
        KastMcpServer(
                catalog = emptyList(),
                invoke = { _, _ -> error("semantic invocation was not requested") },
                root = { CanonicalRootDiscovery.Discovered(selected) },
                onInitialize = { root ->
                    assertEquals(selected, root)
                    starts++
                    Refinement.Refined(Unit)
                },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(
                BufferedInputStream(
                    ByteArrayInputStream(
                        (1..2)
                            .joinToString(separator = "\n", postfix = "\n") {
                                testMcpJson.encodeToString(TestInitializeRequest(id = it))
                            }
                            .toByteArray()
                    )
                ),
                PrintStream(output),
            )
        assertEquals(1, starts)
        assertEquals(2, output.toString(Charsets.UTF_8).lineSequence().count(String::isNotBlank))
    }

    @Test
    fun `preparation rejection is recorded without fabricating readiness`() {
        val diagnostics = ByteArrayOutputStream()
        val output = ByteArrayOutputStream()
        KastMcpServer(
                catalog = emptyList(),
                invoke = { _, _ -> error("semantic invocation was not requested") },
                root = { admittedRoot() },
                onInitialize = {
                    Refinement.Rejected(DaemonOperationFailure.Host(ExistingIdeFailure.CONFIGURATION_REJECTED))
                },
                diagnostic = PrintStream(diagnostics),
            )
            .run(
                BufferedInputStream(
                    ByteArrayInputStream(
                        (testMcpJson.encodeToString(TestInitializeRequest(id = 1)) + "\n").toByteArray()
                    )
                ),
                PrintStream(output),
            )
        assertEquals(1, output.toString(Charsets.UTF_8).lineSequence().count(String::isNotBlank))
        val event = Json.parseToJsonElement(diagnostics.toString(Charsets.UTF_8).trim()).jsonObject
        assertEquals("PREPARATION", event.getValue("stage").jsonPrimitive.content)
        assertEquals("REJECTED", event.getValue("outcome").jsonPrimitive.content)
    }

    @Test
    fun `modern discover prepares the bound root and lists tools without initialize`() {
        val selected = (admittedRoot() as CanonicalRootDiscovery.Discovered).root
        var starts = 0
        val output = ByteArrayOutputStream()
        val requests =
            listOf(
                    TestModernRequest(1, "server/discover"),
                    TestModernRequest(2, "tools/list"),
                )
                .joinToString("\n", postfix = "\n") { testMcpJson.encodeToString(it) }
        KastMcpServer(
                catalog = emptyList(),
                invoke = { _, _ -> error("no call expected") },
                root = { CanonicalRootDiscovery.Discovered(selected) },
                onInitialize = {
                    assertEquals(selected, it)
                    starts++
                    Refinement.Refined(Unit)
                },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(BufferedInputStream(ByteArrayInputStream(requests.toByteArray())), PrintStream(output))
        val results =
            output
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .map { Json.parseToJsonElement(it).jsonObject.getValue("result").jsonObject }
                .toList()
        assertEquals(1, starts)
        assertEquals("2026-07-28", results[0].getValue("supportedVersions").jsonArray.single().jsonPrimitive.content)
        assertEquals("complete", results[1].getValue("resultType").jsonPrimitive.content)
        assertTrue(results[1].getValue("tools").jsonArray.isEmpty())
    }

    @Test
    fun `modern request requires both metadata fields and rejects unsupported version`() {
        val output = ByteArrayOutputStream()
        val requests =
            listOf(
                    TestModernRequest(1, "tools/list", TestModernParams(TestModernMeta(clientCapabilities = null))),
                    TestModernRequest(
                        2,
                        "tools/list",
                        TestModernParams(TestModernMeta(protocolVersion = "2099-01-01")),
                    ),
                )
                .joinToString("\n", postfix = "\n") { testMcpJson.encodeToString(it) }
        KastMcpServer(
                catalog = emptyList(),
                invoke = { _, _ -> error("no call expected") },
                root = { error("no preparation expected") },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(BufferedInputStream(ByteArrayInputStream(requests.toByteArray())), PrintStream(output))
        val errors =
            output
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .map { Json.parseToJsonElement(it).jsonObject.getValue("error").jsonObject }
                .toList()
        assertEquals(-32602, errors[0].getValue("code").jsonPrimitive.content.toInt())
        assertEquals(-32022, errors[1].getValue("code").jsonPrimitive.content.toInt())
        assertEquals(
            "2026-07-28",
            errors[1].getValue("data").jsonObject.getValue("supported").jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    @Suppress("LongMethod")
    fun `modern client receives a model summary and a separate rendered view resource`() {
        val document =
            CanonicalJsonDocument.generated(McpValidationResult.serializer())
                .create(
                    McpValidationResult(
                        data =
                            McpValidationData(
                                McpProbe.passed("Found declaration"),
                                McpProbe.passed("Exact identity"),
                                McpProbe.unverified("Source not requested"),
                                McpProbe.unverified("Relation not requested"),
                                McpProbe.passed("No diagnostics"),
                            )
                    )
                )
        val output = ByteArrayOutputStream()
        var preparations = 0
        val requests =
            listOf(
                    TestModernRequest(
                        1,
                        "tools/call",
                        TestModernParams(name = "validate_workspace", arguments = TestEmptyArguments()),
                    ),
                    TestModernRequest(2, "tools/list"),
                    TestModernRequest(3, "resources/read", TestModernParams(uri = "ui://kast/validation")),
                )
                .joinToString("\n", postfix = "\n") { testMcpJson.encodeToString(it) }
        KastMcpServer(
                catalog = emptyList(),
                invoke = { _, _ -> error("canonical call not expected") },
                root = { admittedRoot() },
                supplemental =
                    listOf(
                        McpSupplementalTool(
                            "validate_workspace",
                            "Check workspace probes",
                            Json.encodeToJsonElement(TestSchema("object")),
                        ) {
                            CliExit.Complete(document)
                        }
                    ),
                onInitialize = {
                    preparations++
                    Refinement.Refined(Unit)
                },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(BufferedInputStream(ByteArrayInputStream(requests.toByteArray())), PrintStream(output))
        val results =
            output
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .map { Json.parseToJsonElement(it).jsonObject.getValue("result").jsonObject }
                .toList()
        assertEquals(1, preparations)
        val tool = results[1].getValue("tools").jsonArray.single().jsonObject
        assertEquals(
            "ui://kast/validation",
            tool.getValue("_meta").jsonObject.getValue("ui").jsonObject.getValue("resourceUri").jsonPrimitive.content,
        )
        assertEquals("object", tool.getValue("outputSchema").jsonObject.getValue("type").jsonPrimitive.content)
        val call = results[0]
        assertEquals("complete", call.getValue("resultType").jsonPrimitive.content)
        assertEquals(
            "discovery passed; exactInspection passed; sourceRead unverified; relation unverified; diagnostics passed",
            call.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertEquals(
            "unverified",
            call
                .getValue("structuredContent")
                .jsonObject
                .getValue("data")
                .jsonObject
                .getValue("relation")
                .jsonObject
                .getValue("status")
                .jsonPrimitive
                .content,
        )
        val resource = results[2].getValue("contents").jsonArray.single().jsonObject
        assertEquals("text/html;profile=mcp-app", resource.getValue("mimeType").jsonPrimitive.content)
        assertTrue(resource.getValue("text").jsonPrimitive.content.contains("ui/notifications/tool-result"))
    }

    @Test
    fun `invalid structured result fails closed with a typed tool outcome`() {
        val invalid =
            CanonicalJsonDocument.generated(TestInvalidStructured.serializer()).create(TestInvalidStructured())
        val diagnostics = ByteArrayOutputStream()
        val output = ByteArrayOutputStream()
        val request =
            testMcpJson.encodeToString(
                TestModernRequest(
                    1,
                    "tools/call",
                    TestModernParams(name = "validate_workspace", arguments = TestEmptyArguments()),
                )
            )
        KastMcpServer(
                catalog = emptyList(),
                invoke = { _, _ -> error("canonical call not expected") },
                root = { admittedRoot() },
                supplemental =
                    listOf(
                        McpSupplementalTool(
                            "validate_workspace",
                            "Check workspace probes",
                            Json.encodeToJsonElement(TestSchema("object")),
                        ) {
                            CliExit.Complete(invalid)
                        }
                    ),
                diagnostic = PrintStream(diagnostics),
            )
            .run(BufferedInputStream(ByteArrayInputStream((request + "\n").toByteArray())), PrintStream(output))
        val result =
            Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject.getValue("result").jsonObject
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
        assertEquals("true", result.getValue("isError").jsonPrimitive.content)
        assertTrue(diagnostics.toString(Charsets.UTF_8).contains("\"outcome\":\"REJECTED\""))
        assertTrue(diagnostics.toString(Charsets.UTF_8).contains("\"resultVariant\":\"COMPLETE\""))
        assertTrue(diagnostics.toString(Charsets.UTF_8).contains("\"schemaFailure\":\"SCHEMA_VIOLATION\""))
        assertTrue(!diagnostics.toString(Charsets.UTF_8).contains("\"outcome\":\"COMPLETED\""))
    }

    @Test
    @Suppress("LongMethod")
    fun `handshake lists the admitted catalog and rejects a call outside Gradle`() {
        var calls = 0
        val diagnostics = ByteArrayOutputStream()
        val server =
            KastMcpServer(
                catalog =
                    listOf(
                        InstalledHostedToolDocument(
                            operationId = "query.run",
                            name = "search_classes",
                            description = "Search classes",
                            deferLoading = false,
                            effect = "intellij_read",
                            approvalPolicy = "none",
                            executionBudget = InstalledServerExecutionBudgetDocument(1000, 1000),
                            inputSchema = Json.encodeToJsonElement(TestSchema("object")),
                            outputSchema = Json.encodeToJsonElement(TestSchema("object")),
                        )
                    ),
                invoke = { _, _ ->
                    calls++
                    error("must not invoke")
                },
                root = { CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND) },
                diagnostic = PrintStream(diagnostics),
            )
        val input =
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_classes","arguments":{}}}
            """
                .trimIndent() + "\n"
        val output = ByteArrayOutputStream()
        server.run(BufferedInputStream(ByteArrayInputStream(input.toByteArray())), PrintStream(output))
        val replies =
            output
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .map { Json.parseToJsonElement(it).jsonObject }
                .toList()
        assertEquals(listOf("1", "2", "3"), replies.map { it.getValue("id").jsonPrimitive.content })
        val instructions = replies[0].getValue("result").jsonObject.getValue("instructions").jsonPrimitive.content
        assertTrue(instructions.contains("IDE preparation"))
        assertTrue(instructions.contains("read_relations"))
        assertTrue(instructions.contains("continuation"))
        assertTrue(instructions.contains("UNSUPPORTED_ITEM"))
        assertTrue(instructions.contains("MODEL_CAPTURE_REJECTED"))
        assertEquals(
            "search_classes",
            replies[1]
                .getValue("result")
                .jsonObject
                .getValue("tools")
                .jsonArray
                .single()
                .jsonObject
                .getValue("name")
                .jsonPrimitive
                .content,
        )
        assertTrue(replies[2].getValue("result").jsonObject.getValue("isError").jsonPrimitive.content.toBoolean())
        assertEquals(0, calls)
        assertTrue(diagnostics.toString(Charsets.UTF_8).contains("\"outcome\":\"REJECTED\""))
    }

    @Test
    @Suppress("LongMethod")
    fun `non JSON operation result is rejected after one CLI invocation`() {
        val diagnostics = ByteArrayOutputStream()
        val output = ByteArrayOutputStream()
        var calls = 0
        val document = (CliTextDocument.admit("complete") as CliTextDocumentAdmission.Admitted).document
        KastMcpServer(
                catalog =
                    listOf(
                        InstalledHostedToolDocument(
                            "query.run",
                            "search_classes",
                            "Search classes",
                            false,
                            "intellij_read",
                            "none",
                            InstalledServerExecutionBudgetDocument(1000, 1000),
                            Json.encodeToJsonElement(TestSchema("object")),
                            Json.encodeToJsonElement(TestSchema("object")),
                        )
                    ),
                invoke = { _, _ ->
                    calls++
                    CliExit.Complete(document)
                },
                root = { admittedRoot() },
                diagnostic = PrintStream(diagnostics),
            )
            .run(
                BufferedInputStream(
                    ByteArrayInputStream(
                        (testMcpJson.encodeToString(TestInitializeRequest(id = 0)) +
                                "\n" +
                                Json { encodeDefaults = true }
                                    .encodeToString(
                                        TestCallRequest(params = TestCallParams("search_classes", TestEmptyArguments()))
                                    ) +
                                "\n")
                            .toByteArray()
                    )
                ),
                PrintStream(output),
            )
        assertEquals(1, calls)
        val result =
            Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim().lineSequence().last())
                .jsonObject
                .getValue("result")
                .jsonObject
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
        assertTrue(diagnostics.toString(Charsets.UTF_8).contains("\"outcome\":\"REJECTED\""))
    }

    @Test
    @Suppress("LongMethod")
    fun `semantic call returns a compact summary and structured evidence`() {
        val document = CanonicalJsonDocument.generated(TestSearchPayload.serializer()).create(TestSearchPayload())
        val output = ByteArrayOutputStream()
        KastMcpServer(
                catalog =
                    listOf(
                        InstalledHostedToolDocument(
                            "query.run",
                            "search_classes",
                            "Search classes",
                            false,
                            "intellij_read",
                            "none",
                            InstalledServerExecutionBudgetDocument(1000, 1000),
                            Json.encodeToJsonElement(TestSchema("object")),
                            Json.encodeToJsonElement(TestSchema("object")),
                        )
                    ),
                invoke = { _, _ -> CliExit.Complete(document) },
                root = { admittedRoot() },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(
                BufferedInputStream(
                    ByteArrayInputStream(
                        (testMcpJson.encodeToString(TestInitializeRequest(id = 0)) +
                                "\n" +
                                Json { encodeDefaults = true }
                                    .encodeToString(
                                        TestCallRequest(params = TestCallParams("search_classes", TestEmptyArguments()))
                                    ) +
                                "\n")
                            .toByteArray()
                    )
                ),
                PrintStream(output),
            )
        val result =
            Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim().lineSequence().last())
                .jsonObject
                .getValue("result")
                .jsonObject
        val content = result.getValue("content").jsonArray
        assertEquals(
            "0 results; requested scope exhausted",
            content.first().jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertEquals(document.value, content.last().jsonObject.getValue("text").jsonPrimitive.content)
        val structured = result.getValue("structuredContent").jsonObject
        assertEquals("complete", structured.getValue("status").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(document.value), structured.getValue("data"))
    }
}

@Serializable private data class TestSchema(val type: String)

@Serializable private data class TestInvalidStructured(val status: String = "complete")

@Serializable
private data class TestModernRequest(
    val id: Int,
    val method: String,
    val params: TestModernParams = TestModernParams(),
    val jsonrpc: String = "2.0",
)

@Serializable
private data class TestModernParams(
    @SerialName("_meta") val meta: TestModernMeta = TestModernMeta(),
    val name: String? = null,
    val arguments: TestEmptyArguments? = null,
    val uri: String? = null,
)

@Serializable
private data class TestModernMeta(
    @SerialName("io.modelcontextprotocol/protocolVersion") val protocolVersion: String = "2026-07-28",
    @SerialName("io.modelcontextprotocol/clientCapabilities")
    val clientCapabilities: TestEmptyArguments? = TestEmptyArguments(),
)

private val testMcpJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
private data class TestInitializeRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String = "initialize",
    val params: TestInitializeParams = TestInitializeParams(),
)

@Serializable private data class TestInitializeParams(val protocolVersion: String = "2025-06-18")

@Serializable
private data class TestCallRequest(
    val jsonrpc: String = "2.0",
    val id: Int = 1,
    val method: String = "tools/call",
    val params: TestCallParams,
)

@Serializable private data class TestCallParams(val name: String, val arguments: TestEmptyArguments)

@Serializable private class TestEmptyArguments

@Serializable
private data class TestSearchPayload(
    val operation: String = "query.run",
    val status: String = "complete",
    val items: List<String> = emptyList(),
    val coverage: TestSearchCoverage = TestSearchCoverage(),
    val live: TestSearchLive = TestSearchLive(),
)

@Serializable private data class TestSearchCoverage(val exhaustive: Boolean = true)

@Serializable
private data class TestSearchLive(
    val root: String = "/workspace",
    val host: String = "host-1",
    val epoch: Int = 1,
    val contentView: String = "SAVED_PSI_COMMITTED",
    val version: Int = 1,
)
