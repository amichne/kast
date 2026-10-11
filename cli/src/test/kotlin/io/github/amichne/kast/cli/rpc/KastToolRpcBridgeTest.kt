package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.LiveReadOutputSchemaTest
import io.github.amichne.kast.cli.direct.DirectToolRegistration
import io.github.amichne.kast.cli.direct.InstalledToolAdmission
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.direct.directSupportTools
import io.github.amichne.kast.cli.direct.directToolDocument
import io.github.amichne.kast.cli.installedHostedBootstrap
import io.github.amichne.kast.cli.mcp.KastMcpServer
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeRejection
import io.github.amichne.kast.protocol.contract.ChangeRunDocument
import io.github.amichne.kast.protocol.contract.ChangeRunError
import io.github.amichne.kast.protocol.registry.AgentToolName
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.registry.SupportToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastToolRpcBridgeTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `one shot invocation preserves canonical admission without projecting a catalog`() {
        val name = (AgentToolName.parse(PublicToolIdentity.QUERY_SYMBOLS.toolName) as Refinement.Refined).value
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val root = (FilesystemCanonicalRootDiscovery.discover(temporary) as CanonicalRootDiscovery.Discovered).root
        val document = completeSourceDocument()
        var invocations = 0
        var preparations = 0
        val session =
            KastDirectToolSession(
                registration =
                    DirectToolRegistration(setOf(name)) { error("Invocation must not build discovery schemas") },
                root = { CanonicalRootDiscovery.Discovered(root) },
                start = {
                    preparations++
                    Refinement.Refined(Unit)
                },
                invokePublic = { admitted ->
                    assertEquals(PublicToolIdentity.QUERY_SYMBOLS, admitted.identity)
                    invocations++
                    CliExit.Complete(document)
                },
            )
        val bridge = KastToolRpcBridge(session)
        assertEquals(ToolRpcReply.Rejected(ToolRpcFailure.UNKNOWN_TOOL), bridge.call("missing", "["))
        assertEquals(ToolRpcReply.Rejected(ToolRpcFailure.INVALID_ARGUMENTS), bridge.call(name.value, "["))
        assertInstanceOf(
            ToolRpcReply.Complete::class.java,
            bridge.call(name.value, publicExample(PublicToolIdentity.QUERY_SYMBOLS, "runByName")),
        )
        assertEquals(1, invocations)
        assertEquals(1, preparations)
    }

    @Test
    fun `MCP and RPC catalogs preserve the same installed input schemas`() {
        val installed = installedHostedBootstrap().tools
        val publicTools = installed.filter {
            it.name in setOf("query_symbols", "check_diagnostics", "add_declaration", "replace_body")
        }
        val session =
            KastDirectToolSession(
                catalog = publicTools.map { it.directToolDocument() },
                root = { CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND) },
                start = { error("no preparation expected") },
                invokePublic = { error("no call expected") },
            )
        val rpc = (KastToolRpcBridge(session).catalog() as ToolRpcReply.Catalog).catalog.tools.associateBy { it.name }
        val mcp = mcpCatalog(session)
        assertEquals(rpc.keys, mcp.keys)
        assertEquals(false, "validate_workspace" in rpc)
        assertEquals(false, "read_source" in rpc)
        rpc.forEach { (name, tool) ->
            assertEquals(tool.inputSchema, mcp.getValue(name).jsonObject.getValue("inputSchema"), name)
        }
    }

    @Test
    fun `real adapters admit the production generated catalog before registration`() {
        val session =
            KastDirectToolSession(
                catalog =
                    installedHostedBootstrap()
                        .tools
                        .filter { tool -> PublicToolIdentity.entries.any { it.toolName == tool.name } }
                        .map { it.directToolDocument() } + directSupportTools(),
                root = { error("catalog must not inspect the workspace") },
                start = { error("catalog must not prepare the workspace") },
                invokePublic = { error("catalog must not invoke a tool") },
            )
        val catalog = temporary.resolve("catalog.json")
        Files.writeString(catalog, Json.encodeToString<ToolRpcReply>(KastToolRpcBridge(session).catalog()))
        val failures = temporary.resolve("failures.json")
        Files.writeString(failures, Json.encodeToString(ToolRpcFailure.entries.toList()))
        val delivery = temporary.resolve("query-delivery.json")
        Files.writeString(delivery, QueryDeliveryFixtures.serialized())
        val output = Path.of("build/reports/query-delivery/adapter-test.log")
        Files.createDirectories(output.parent)
        val process =
            ProcessBuilder("node", "--experimental-vm-modules", "src/test/js/harness-adapters.test.mjs")
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .apply {
                    environment()["KAST_ADAPTER_TEST_CATALOG"] = catalog.toString()
                    environment()["KAST_ADAPTER_TEST_FAILURES"] = failures.toString()
                    environment()["KAST_ADAPTER_TEST_DELIVERY"] = delivery.toString()
                }
                .start()
        try {
            org.junit.jupiter.api.Assertions.assertTrue(
                process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
                Files.readString(output),
            )
            assertEquals(0, process.exitValue(), Files.readString(output))
        } finally {
            process.destroyForcibly()
        }
    }

    private fun mcpCatalog(session: KastDirectToolSession): Map<String, JsonElement> {
        val output = ByteArrayOutputStream()
        val requests =
            listOf(TestCatalogRequest(1, "initialize"), TestCatalogRequest(2, "tools/list")).joinToString(
                "\n",
                postfix = "\n",
            ) {
                Json.encodeToString(it)
            }
        KastMcpServer(
                catalog = session.catalog,
                invoke = session::invoke,
                root = session.root,
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(BufferedInputStream(ByteArrayInputStream(requests.toByteArray())), PrintStream(output))
        return Json.parseToJsonElement(output.toString(Charsets.UTF_8).lineSequence().last { it.isNotBlank() })
            .jsonObject
            .getValue("result")
            .jsonObject
            .getValue("tools")
            .jsonArray
            .associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
    }

    @Test
    fun `catalog exposes direct tools and invokes one native read without an MCP exchange`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val read = installedHostedBootstrap().tools.single { it.name == "query_symbols" }
        val change = installedHostedBootstrap().tools.single { it.name == "add_declaration" }
        val document = completeSourceDocument()
        var preparationCount = 0
        var invokedName: String? = null
        val session =
            KastDirectToolSession(
                catalog =
                    listOf(read.directToolDocument(), change.directToolDocument()) +
                        directSupportTools().filter { it.name == "health_check" },
                root = { FilesystemCanonicalRootDiscovery.discover(temporary) },
                start = {
                    preparationCount++
                    Refinement.Refined(Unit)
                },
                invokeSupport = { identity, _ ->
                    assertEquals(SupportToolIdentity.HEALTH_CHECK, identity)
                    CliExit.Complete(document)
                },
                invokePublic = { request ->
                    invokedName = request.identity.toolName
                    if (request.identity == PublicToolIdentity.ADD_DECLARATION)
                        CliExit.OperationRejected(changeRejectionDocument())
                    else CliExit.Complete(document)
                },
            )
        val bridge = KastToolRpcBridge(session)
        val catalog = assertInstanceOf(ToolRpcReply.Catalog::class.java, bridge.catalog()).catalog
        assertEquals(14, catalog.schemaVersion)
        assertEquals(listOf("add_declaration", "health_check", "query_symbols"), catalog.tools.map { it.name })
        assertEquals(
            listOf(ToolRpcToolEffect.WRITE, ToolRpcToolEffect.READ, ToolRpcToolEffect.READ),
            catalog.tools.map { it.effect },
        )
        val queryRequest = publicExample(PublicToolIdentity.QUERY_SYMBOLS, "runByName")
        val complete = assertInstanceOf(ToolRpcReply.Complete::class.java, bridge.call("query_symbols", queryRequest))
        assertEquals("complete", complete.document.jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("query_symbols", invokedName)
        assertEquals(1, preparationCount)
        val changeRequest = publicExample(PublicToolIdentity.ADD_DECLARATION, "exactTarget")
        assertInstanceOf(ToolRpcReply.RejectedDocument::class.java, bridge.call("add_declaration", changeRequest))
        assertEquals(2, preparationCount)
    }

    @Test
    fun `shutdown fencing rejects RPC before workspace discovery preparation or semantic dispatch`() {
        val read = installedHostedBootstrap().tools.single { it.name == "query_symbols" }
        val session =
            KastDirectToolSession(
                catalog = listOf(read.directToolDocument()),
                root = { error("shutdown must precede workspace discovery") },
                start = { error("shutdown must precede preparation") },
                invokePublic = { error("shutdown must precede semantic dispatch") },
                admission = { InstalledToolAdmission.STOPPED },
            )
        assertEquals(
            ToolRpcReply.Rejected(ToolRpcFailure.INSTALLATION_STOPPED),
            KastToolRpcBridge(session)
                .call("query_symbols", publicExample(PublicToolIdentity.QUERY_SYMBOLS, "runByName")),
        )
    }

    private fun completeSourceDocument(): CanonicalJsonDocument {
        val fixture = LiveReadOutputSchemaTest()
        val basis = EvidenceBasis.Published((EvidenceGeneration.parse(1) as Refinement.Refined).value)
        return (CanonicalSourceReadCliDocuments.project(
                OperationOutcome.Complete(
                    EvidenceEnvelope(CanonicalOperation.SOURCE_READ.id, basis, fixture.sourceResult(basis))
                )
            ) as ProjectedOperationOutcome.Complete)
            .document
    }

    private fun changeRejectionDocument(): CanonicalJsonDocument =
        CanonicalJsonDocument.generated(ChangeRunDocument.serializer())
            .create(ChangeRunDocument.Rejected(ChangeRunError(ChangeRejection.PLANNING_REJECTED)))

    private fun publicExample(identity: PublicToolIdentity, name: String): String =
        PublicToolContract.examples(identity).examples.getValue(name).value.toString()

    @Test
    fun `unknown and malformed calls reject before native preparation`() {
        val session =
            KastDirectToolSession(
                catalog =
                    listOf(installedHostedBootstrap().tools.single { it.name == "query_symbols" }.directToolDocument()),
                root = { error("root must not be inspected") },
                start = { error("preparation must not start") },
                invokePublic = { error("operation must not run") },
            )
        val bridge = KastToolRpcBridge(session)
        assertEquals(
            ToolRpcFailure.UNKNOWN_TOOL,
            (bridge.call("missing", Json.encodeToString(TestEmptyRequest())) as ToolRpcReply.Rejected).failure,
        )
        assertEquals(
            ToolRpcFailure.UNKNOWN_TOOL,
            (bridge.call("validate_workspace", Json.encodeToString(TestEmptyRequest())) as ToolRpcReply.Rejected)
                .failure,
        )
        assertEquals(
            ToolRpcFailure.INVALID_ARGUMENTS,
            (bridge.call("query_symbols", "[") as ToolRpcReply.Rejected).failure,
        )
        assertEquals(
            ToolRpcFailure.INVALID_ARGUMENTS,
            (bridge.call("query_symbols", Json.encodeToString(TestEmptyRequest())) as ToolRpcReply.Rejected).failure,
        )
    }

    @Test
    fun `wire keeps every closed outcome and catalog discriminator`() {
        val json = Json { encodeDefaults = true }
        val document = json.encodeToJsonElement(TestResult()).jsonObject
        val cases =
            listOf(
                ToolRpcReply.Complete(document) to "complete",
                ToolRpcReply.Qualified(document) to "qualified",
                ToolRpcReply.RejectedDocument(document) to "rejected_document",
                ToolRpcReply.Rejected(ToolRpcFailure.OUT_OF_SCOPE) to "rejected",
                ToolRpcReply.Catalog(ToolRpcCatalog(emptyList())) to "catalog",
            )
        for ((reply, expectedType) in cases) {
            val encoded = json.encodeToJsonElement<ToolRpcReply>(reply).jsonObject
            assertEquals(expectedType, encoded.getValue("type").jsonPrimitive.content)
            if (reply is ToolRpcReply.Catalog) {
                val catalog = encoded.getValue("catalog").jsonObject
                assertEquals("14", catalog.getValue("schemaVersion").jsonPrimitive.content)
                assertEquals(0, catalog.getValue("tools").jsonArray.size)
            } else if (reply is ToolRpcReply.Rejected) {
                assertEquals("OUT_OF_SCOPE", encoded.getValue("failure").jsonPrimitive.content)
            } else {
                assertEquals(
                    "complete",
                    encoded.getValue("document").jsonObject.getValue("status").jsonPrimitive.content,
                )
            }
        }
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<ToolRpcReply>(json.encodeToString(TestUnknownVariant()))
        }
    }
}

@Serializable
private data class TestCatalogRequest(val id: Int, val method: String, @Required val jsonrpc: String = "2.0")

@Serializable private class TestEmptyRequest

@Serializable private data class TestResult(val status: TestStatus = TestStatus.COMPLETE)

@Serializable
private enum class TestStatus {
    @SerialName("complete") COMPLETE
}

@Serializable private data class TestUnknownVariant(val type: String = "unknown")
