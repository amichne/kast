package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.host.admission.DesktopFacadeExecutables
import io.github.amichne.kast.appserver.host.admission.UpstreamCodexExecutable
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Native admission only; a tool dispatch additionally requires an admitted installed IDEA workspace. */
class NativeCatalogAdmissionTest {
    @Test
    fun `installed Codex accepts the generated namespace at thread startup`(@TempDir temporary: Path) = runBlocking {
        val configured = System.getenv("KAST_CODEX_ALIAS_ACCEPTANCE_EXECUTABLE")
        assumeTrue(!configured.isNullOrBlank(), "Set KAST_CODEX_ALIAS_ACCEPTANCE_EXECUTABLE for native admission")
        val executable =
            (UpstreamCodexExecutable.admit(Path.of(configured).toRealPath(), DesktopFacadeExecutables.none())
                    as Refinement.Refined)
                .value
        val home = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-catalog-${UUID.randomUUID()}.sock")
        val started =
            ManagedCodexUpstream.start(
                ManagedCodexUpstreamOptions(
                    executable = executable,
                    codexHome = home,
                    privateSocket = (BrokerSocketPath.admit(socket) as Validation.Validated).value,
                    maximumMessageBytes = 4 * 1024 * 1024,
                    startupTimeoutMillis = 10_000,
                )
            ) as ManagedCodexUpstreamStart.Started
        try {
            val connection = (started.upstream.connect() as BrokerUpstreamConnectionAdmission.Connected).connection
            try {
                withTimeout(30_000) { verifyAdmission(connection, temporary.toRealPath()) }
            } finally {
                connection.close()
            }
        } finally {
            started.upstream.close()
        }
    }

    private suspend fun verifyAdmission(connection: BrokerUpstreamConnection, workspace: Path) {
        val json = Json { encodeDefaults = true }
        connection.send(
            json.encodeToString(
                InitializeRequest(1, InitializeParams(ClientInfo("kast-native-catalog", "1"), Capabilities(true)))
            )
        )
        val initialized = response(connection, "1")
        assertNull(initialized["error"], "initialize: $initialized")
        assertNotNull(initialized["result"])
        connection.send(json.encodeToString(Initialized()))
        val catalog =
            javaClass
                .getResourceAsStream("/io/github/amichne/kast/appserver/query/tools.app-server.json")!!
                .bufferedReader()
                .use { it.readText() }
        val namespace = json.decodeFromString<NativeNamespace>(catalog)
        val unrelated =
            NativeNamespace(
                "namespace",
                "unrelated",
                "Preserved host tools",
                listOf(NativeTool("function", "probe", "Host probe", json.encodeToJsonElement(ProbeSchema()))),
            )
        connection.send(
            json.encodeToString(
                StartRequest(
                    2,
                    StartParams(workspace.toString(), listOf(unrelated, namespace), "Preserve this host instruction."),
                )
            )
        )
        val started = response(connection, "2")
        assertNull(started["error"], "thread/start: $started")
        val result = started.getValue("result").jsonObject
        assertEquals(workspace.toString(), result.getValue("cwd").jsonPrimitive.content)
        assertNotNull(result.getValue("thread").jsonObject["id"])
    }

    private suspend fun response(connection: BrokerUpstreamConnection, id: String): JsonObject {
        repeat(32) {
            val frame = connection.receive() as BrokerUpstreamFrame.Text
            val document = Json.parseToJsonElement(frame.message).jsonObject
            if (document["id"]?.jsonPrimitive?.content == id) return document
        }
        error("Native admission response not received within the bounded notification count")
    }
}

@Serializable
private data class InitializeRequest(val id: Int, val params: InitializeParams, val method: String = "initialize")

@Serializable private data class InitializeParams(val clientInfo: ClientInfo, val capabilities: Capabilities)

@Serializable private data class ClientInfo(val name: String, val version: String)

@Serializable private data class Capabilities(val experimentalApi: Boolean)

@Serializable private data class Initialized(val method: String = "initialized")

@Serializable private data class StartRequest(val id: Int, val params: StartParams, val method: String = "thread/start")

@Serializable
private data class StartParams(
    val cwd: String,
    val dynamicTools: List<NativeNamespace>,
    val developerInstructions: String,
    val ephemeral: Boolean = true,
)

@Serializable
private data class NativeNamespace(
    val type: String,
    val name: String,
    val description: String,
    val tools: List<NativeTool>,
)

/** inputSchema is the contract-defined dynamic JSON Schema, copied from the generated catalog. */
@Serializable
private data class NativeTool(
    val type: String,
    val name: String,
    val description: String,
    val inputSchema: kotlinx.serialization.json.JsonElement,
    val deferLoading: Boolean = false,
)

@Serializable private data class ProbeSchema(val type: String = "object")
