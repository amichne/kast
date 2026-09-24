package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.SymbolDiscoveryCliDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpInvestigationToolsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `health reports native readiness without making a semantic call`() {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        var statusCalls = 0
        val native = ExistingIdeClient { selected, operation ->
            assertEquals(root.toRealPath(), selected.path)
            assertEquals(ExistingIdeOperation.Status, operation)
            statusCalls++
            ExistingIdeExchange.Received(
                CanonicalJsonDocument.generated(TestHostedStatus.serializer())
                    .create(TestHostedStatus(root.toRealPath().toString()))
            )
        }
        val capabilities = ExistingIdeCliCapabilities(FilesystemCanonicalRootDiscovery, native)
        val tools =
            McpInvestigationTools(root, capabilities, setOf("QUERY_RUN", "SOURCE_READ")) { _, _ ->
                error("semantic read must not run")
            }
        val exit = tools.tools.single { it.name == "health_check" }.invoke(emptyArguments())
        assertTrue(exit is CliExit.Complete, exit.document.value)
        val data = Json.parseToJsonElement(exit.document.value).jsonObject.getValue("data").jsonObject
        assertEquals("READY", data.getValue("readiness").jsonPrimitive.content)
        assertEquals("INDEXED", data.getValue("hostState").jsonPrimitive.content)
        assertTrue("supportedCapabilities" !in data)
        assertTrue("unavailableCapabilities" !in data)
        assertEquals(1, statusCalls)
    }

    @Test
    fun `health binds a launch subdirectory to its settings owned root`() {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val launchDirectory = Files.createDirectories(root.resolve("src/main/kotlin"))
        val canonicalRoot = root.toRealPath()
        val native = ExistingIdeClient { selected, operation ->
            assertEquals(canonicalRoot, selected.path)
            assertEquals(ExistingIdeOperation.Status, operation)
            ExistingIdeExchange.Received(
                CanonicalJsonDocument.generated(TestHostedStatus.serializer())
                    .create(TestHostedStatus(canonicalRoot.toString()))
            )
        }
        val tools =
            McpInvestigationTools(
                launchDirectory,
                ExistingIdeCliCapabilities(FilesystemCanonicalRootDiscovery, native),
                setOf("QUERY_RUN", "SOURCE_READ"),
            ) { _, _ ->
                error("semantic read must not run")
            }
        val exit = tools.tools.single { it.name == "health_check" }.invoke(emptyArguments())
        assertTrue(exit is CliExit.Complete, exit.document.value)
        val data = Json.parseToJsonElement(exit.document.value).jsonObject.getValue("data").jsonObject
        assertEquals(canonicalRoot.toString(), data.getValue("workspaceBinding").jsonPrimitive.content)
    }

    @Test
    fun `health rejects a host missing required operations without exposing inventory`() {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val native = ExistingIdeClient { selected, operation ->
            assertEquals(ExistingIdeOperation.Status, operation)
            ExistingIdeExchange.Received(
                CanonicalJsonDocument.generated(TestHostedStatus.serializer())
                    .create(TestHostedStatus(selected.path.toString(), operations = emptyList()))
            )
        }
        val tools =
            McpInvestigationTools(
                root,
                ExistingIdeCliCapabilities(FilesystemCanonicalRootDiscovery, native),
                setOf("QUERY_RUN"),
            ) { _, _ ->
                error("semantic read must not run")
            }
        val exit = tools.tools.single { it.name == "health_check" }.invoke(emptyArguments())
        assertTrue(exit is CliExit.OperationRejected)
        val result = Json.parseToJsonElement(exit.document.value).jsonObject
        assertEquals("HOST_UNAVAILABLE", result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertTrue("supportedCapabilities" !in result)
        assertTrue("unavailableCapabilities" !in result)
    }

    @Test
    fun `relation probe chooses the contract oriented subject for every kind`() {
        for (kind in McpValidationRelationKind.entries) {
            assertEquals(
                if (kind == McpValidationRelationKind.CALLEES) "source" else "target",
                kind.subjectSelector("source", "target"),
                kind.name,
            )
        }
    }

    @Test
    fun `missing probes stay unverified without invoking semantics`() {
        val exit = validateWorkspace(emptyArguments(), root) { _, _ -> error("no read expected") }
        assertTrue(exit is CliExit.Complete)
        val data = Json.parseToJsonElement(exit.document.value).jsonObject.getValue("data").jsonObject
        assertEquals(5, data.size)
        assertTrue(data.values.all { it.jsonObject.getValue("status").jsonPrimitive.content == "unverified" })
    }

    @Test
    fun `incomplete discovery cannot pass a present candidate`() {
        val file = "src/main/kotlin/sample/Registry.kt"
        val request = TestValidationRequest(TestDeclaration("class", "Registry", file))
        var calls = 0
        val exit =
            validateWorkspace(Json.encodeToJsonElement(request).jsonObject, root) { name, _ ->
                assertEquals("symbol_lookup", name)
                calls++
                CliExit.Qualified(
                    CanonicalJsonDocument.generated(TestQualifiedDiscovery.serializer())
                        .create(
                            TestQualifiedDiscovery(
                                items =
                                    listOf(
                                        SymbolDiscoveryCliDocument.Declaration(
                                            "candidate:opaque",
                                            "class",
                                            "Registry",
                                            root.resolve(file).toString(),
                                            0,
                                        )
                                    )
                            )
                        )
                )
            }
        val data = Json.parseToJsonElement(exit.document.value).jsonObject.getValue("data").jsonObject
        assertEquals("unverified", data.getValue("discovery").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("unverified", data.getValue("exactInspection").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(1, calls)
    }
}

private fun emptyArguments(): JsonObject = Json.encodeToJsonElement(TestNoArguments()).jsonObject

@Serializable private class TestNoArguments

@Serializable
private data class TestHostedStatus(
    val root: String,
    val host: String = "host-1",
    val type: String = "KAST_IDE_HOST",
    val operations: List<String> = listOf("QUERY_RUN", "SOURCE_READ"),
    val readiness: TestHostedReadiness = TestHostedReadiness(),
)

@Serializable private data class TestHostedReadiness(val status: String = "admission_ready")

@Serializable private data class TestValidationRequest(val declaration: TestDeclaration)

@Serializable private data class TestDeclaration(val kind: String, val name: String, val file: String)

@Serializable
private data class TestQualifiedDiscovery(
    val operation: String = "symbol.discover",
    val status: String = "qualified",
    val items: List<SymbolDiscoveryCliDocument>,
    val qualification: String = "result-limit",
)
