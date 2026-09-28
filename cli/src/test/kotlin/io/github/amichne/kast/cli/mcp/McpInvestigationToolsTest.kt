package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.direct.directSupportTools
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.protocol.registry.SupportToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    fun `public direct support catalog excludes workspace validation`() {
        assertEquals(listOf("health_check"), directSupportTools().map { it.name })
    }

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
        val tools = McpInvestigationTools(root, capabilities, setOf("QUERY_RUN", "SOURCE_READ"))
        val exit = tools.invoke(SupportToolIdentity.HEALTH_CHECK, emptyArguments())
        assertTrue(exit is CliExit.Complete, exit.document.value)
        val data = Json.parseToJsonElement(exit.document.value).jsonObject.getValue("data").jsonObject
        assertEquals("READY", data.getValue("readiness").jsonPrimitive.content)
        assertEquals("INDEXED", data.getValue("hostState").jsonPrimitive.content)
        assertTrue("supportedCapabilities" !in data)
        assertTrue("unavailableCapabilities" !in data)
        assertEquals(1, statusCalls)
    }

    @Test
    fun `health verbose adds host detail and rejects non boolean switches before effects`() {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        var calls = 0
        val native = ExistingIdeClient { selected, _ ->
            calls++
            ExistingIdeExchange.Received(
                CanonicalJsonDocument.generated(TestHostedStatus.serializer())
                    .create(TestHostedStatus(selected.path.toString()))
            )
        }
        val tools =
            McpInvestigationTools(
                root,
                ExistingIdeCliCapabilities(FilesystemCanonicalRootDiscovery, native),
                setOf("QUERY_RUN"),
            )
        for (verbose in listOf(false, true)) {
            val result =
                tools.invoke(
                    SupportToolIdentity.HEALTH_CHECK,
                    Json.encodeToJsonElement(TestVerboseInput(verbose)).jsonObject,
                )
            val data = Json.parseToJsonElement(result.document.value).jsonObject.getValue("data").jsonObject
            assertEquals(verbose, "host" in data)
            assertEquals("READY", data.getValue("readiness").jsonPrimitive.content)
        }
        for (malformed in listOf(JsonNull, JsonPrimitive("true"), JsonPrimitive(1))) {
            val result =
                tools.invoke(
                    SupportToolIdentity.HEALTH_CHECK,
                    Json.encodeToJsonElement(InvalidVerboseInput(malformed)).jsonObject,
                )
            assertTrue(result is CliExit.OperationRejected)
        }
        assertEquals(2, calls)
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
            )
        val exit = tools.invoke(SupportToolIdentity.HEALTH_CHECK, emptyArguments())
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
            )
        val exit = tools.invoke(SupportToolIdentity.HEALTH_CHECK, emptyArguments())
        assertTrue(exit is CliExit.OperationRejected)
        val result = Json.parseToJsonElement(exit.document.value).jsonObject
        assertEquals("HOST_UNAVAILABLE", result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertTrue("supportedCapabilities" !in result)
        assertTrue("unavailableCapabilities" !in result)
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

@Serializable private data class TestVerboseInput(val verbose: Boolean)

/** Deliberately incompatible field types test strict boolean admission. */
@Serializable private data class InvalidVerboseInput(val verbose: JsonElement)
