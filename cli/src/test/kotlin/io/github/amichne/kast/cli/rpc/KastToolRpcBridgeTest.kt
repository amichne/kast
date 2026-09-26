package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.installedHostedBootstrap
import io.github.amichne.kast.cli.mcp.McpSupplementalTool
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    fun `catalog exposes direct tools and invokes one native read without an MCP exchange`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val read = installedHostedBootstrap().tools.single { it.name == "query_symbols" }
        val schema = Json.encodeToJsonElement(TestInputSchema())
        val document = CanonicalJsonDocument.generated(TestResult.serializer()).create(TestResult())
        var preparationCount = 0
        var invokedName: String? = null
        val session =
            KastDirectToolSession(
                catalog = listOf(read),
                supplemental =
                    listOf(
                        McpSupplementalTool("health_check", "Observe readiness", schema) { CliExit.Complete(document) },
                        McpSupplementalTool("change", "Change source", schema, readOnly = false) {
                            CliExit.OperationRejected(document)
                        },
                    ),
                root = { FilesystemCanonicalRootDiscovery.discover(temporary) },
                start = {
                    preparationCount++
                    Refinement.Refined(Unit)
                },
                invokeCanonical = { name, _ ->
                    invokedName = name
                    CliExit.Complete(document)
                },
            )
        val bridge = KastToolRpcBridge(session)
        val catalog = assertInstanceOf(ToolRpcReply.Catalog::class.java, bridge.catalog()).catalog
        assertEquals(1, catalog.schemaVersion)
        assertEquals(listOf("change", "health_check", "query_symbols"), catalog.tools.map { it.name })
        assertEquals(
            listOf(ToolRpcToolEffect.WRITE, ToolRpcToolEffect.READ, ToolRpcToolEffect.READ),
            catalog.tools.map { it.effect },
        )
        val emptyRequest = Json.encodeToString(TestEmptyRequest())
        val complete = assertInstanceOf(ToolRpcReply.Complete::class.java, bridge.call("query_symbols", emptyRequest))
        assertEquals("complete", complete.document.jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("query_symbols", invokedName)
        assertEquals(1, preparationCount)
        assertInstanceOf(ToolRpcReply.RejectedDocument::class.java, bridge.call("change", emptyRequest))
        assertEquals(2, preparationCount)
    }

    @Test
    fun `unknown and malformed calls reject before native preparation`() {
        val session =
            KastDirectToolSession(
                catalog = listOf(installedHostedBootstrap().tools.single { it.name == "query_symbols" }),
                supplemental = emptyList(),
                root = { error("root must not be inspected") },
                start = { error("preparation must not start") },
                invokeCanonical = { _, _ -> error("operation must not run") },
            )
        val bridge = KastToolRpcBridge(session)
        assertEquals(
            ToolRpcFailure.UNKNOWN_TOOL,
            (bridge.call("missing", Json.encodeToString(TestEmptyRequest())) as ToolRpcReply.Rejected).failure,
        )
        assertEquals(
            ToolRpcFailure.INVALID_ARGUMENTS,
            (bridge.call("query_symbols", "[") as ToolRpcReply.Rejected).failure,
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
                assertEquals("1", catalog.getValue("schemaVersion").jsonPrimitive.content)
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

@Serializable private data class TestInputSchema(val type: String = "object")

@Serializable private class TestEmptyRequest

@Serializable private data class TestResult(val status: TestStatus = TestStatus.COMPLETE)

@Serializable
private enum class TestStatus {
    @SerialName("complete") COMPLETE
}

@Serializable private data class TestUnknownVariant(val type: String = "unknown")
