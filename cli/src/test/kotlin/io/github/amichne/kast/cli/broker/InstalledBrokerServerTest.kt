package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.cli.InstalledSchemaConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.installedSchema
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.cli.broker.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.cli.broker.provider.BrokerProcessExecution
import io.github.amichne.kast.cli.broker.provider.BrokerProcessExecutor
import io.github.amichne.kast.cli.broker.provider.BrokerProcessRequest
import io.github.amichne.kast.cli.broker.runtime.CodexAppServerProcess
import io.github.amichne.kast.cli.broker.runtime.CodexAppServerProcessAdmission
import io.github.amichne.kast.cli.broker.runtime.CodexAppServerProcessLauncher
import io.github.amichne.kast.cli.broker.runtime.CodexAppServerProcessRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.server.application.install
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class InstalledBrokerServerTest {
    @Test
    fun `runner retains the exact configuration rejection`(
        @TempDir temporary: Path,
    ) {
        val user = temporary.toRealPath()
        val kast = executable(user.resolve("kast"))

        assertEquals(
            BrokerServerRun.Rejected(BrokerServerFailure.CODEX_EXECUTABLE_REJECTED),
            InstalledBrokerServerRunner(kast, user, emptyMap()).serve(),
        )
    }

    @Test
    fun `tool selection defaults without symbols and admits one exact configured subset`(
        @TempDir temporary: Path,
    ) {
        val suffix = UUID.randomUUID().toString().take(8)
        val codexHome = Path.of("/private/tmp/kast-tool-exposure-$suffix")
        Files.createDirectory(codexHome)
        val user = temporary.toRealPath()
        val kast = executable(user.resolve("kast"))
        val codex = executable(user.resolve("codex"))
        val base = mapOf(
            "CODEX_HOME" to codexHome.toString(),
            "CODEX_EXECUTABLE" to codex.toString(),
        )
        try {
            fun configured(environment: Map<String, String>) =
                (InstalledBrokerServerConfiguration.admit(kast, user, environment) as
                    InstalledBrokerServerConfiguration.Configured).options

            assertEquals(
                "query,source_read,semantic_query,impact_analyze,diagnostic_check," +
                    "change_plan,change_apply,change_recover",
                configured(base).kastOptions.toolSelection.environmentValue,
            )
            assertEquals(
                "query,diagnostic_check,change_apply",
                configured(
                    base + ("KAST_APP_SERVER_TOOLS" to "change_apply,query,diagnostic_check"),
                ).kastOptions.toolSelection.environmentValue,
            )
            assertEquals(
                InstalledBrokerServerConfiguration.Rejected(
                    InstalledBrokerServerConfigurationFailure.PROVIDER_CONFIGURATION_REJECTED,
                ),
                InstalledBrokerServerConfiguration.admit(
                    kast,
                    user,
                    base + ("KAST_APP_SERVER_TOOLS" to "query,symbol_unknown"),
                ),
            )
            assertEquals(
                InstalledBrokerServerConfiguration.Rejected(
                    InstalledBrokerServerConfigurationFailure.APP_SERVER_DISABLED,
                ),
                InstalledBrokerServerConfiguration.admit(
                    kast,
                    user,
                    base + ("KAST_ENABLE_APP_SERVER" to "0"),
                ),
            )
        } finally {
            retireOwnedTree(codexHome)
        }
    }

    @Test
    fun `integration transport owns a distinct socket namespace from legacy broker`(@TempDir temporary: Path) {
        val home = Path.of("/private/tmp/kast-host-" + UUID.randomUUID().toString().take(8))
        Files.createDirectory(home)
        try {
            val user = temporary.toRealPath()
            val kast = executable(user.resolve("kast"))
            val codex = executable(user.resolve("codex"))
            val environment = mapOf("CODEX_HOME" to home.toString(), "CODEX_EXECUTABLE" to codex.toString())
            fun options(transport: BrokerClientTransport) = (InstalledBrokerServerConfiguration.admit(
                kast, user, environment, clientTransport = transport,
            ) as InstalledBrokerServerConfiguration.Configured).options
            val legacy = options(BrokerClientTransport.LEGACY_CONTROL)
            val integration = options(BrokerClientTransport.INTEGRATION_OWNED)
            assertFalse(legacy.publicSocket == integration.publicSocket)
            assertFalse(legacy.upstreamOptions.privateSocket == integration.upstreamOptions.privateSocket)
            assertTrue(integration.publicSocket.path.startsWith(home.resolve("kast-integration")))
        } finally { retireOwnedTree(home) }
    }

    @Test
    fun `upstream rejection retains exact finite reason without process output`() {
        val output = ByteArrayOutputStream()
        val activity = BrokerStartupActivityPublisher(JsonLineBrokerStartupActivitySink(PrintStream(output)))
        activity.started(BrokerStartupStage.UPSTREAM)
        activity.rejected(BrokerStartupStage.UPSTREAM, BrokerStartupRejection.Upstream(
            io.github.amichne.kast.cli.broker.runtime.ManagedCodexUpstreamFailure.SOCKET_PATH_OWNED,
        ))
        assertTrue(output.toString().contains("\"reason\":\"upstream-socket-path-owned\""))
        assertEquals(2, output.toString().lineSequence().filter(String::isNotBlank).count())
    }

    @Test
    fun `managed startup publishes its exact finite rejection instead of timing out`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val codexHome = Path.of("/private/tmp/kast-service-rejected-$suffix")
        Files.createDirectory(codexHome)
        val userHome = temporary.toRealPath()
        val kast = executable(userHome.resolve("kast"))
        val codex = executable(userHome.resolve("codex"))
        val readiness = codexHome.resolve("broker/service-readiness.json")
        val identity = "sha256:${"b".repeat(64)}"
        try {
            val configuration = InstalledBrokerServerConfiguration.admit(
                kast,
                userHome,
                mapOf(
                    "CODEX_HOME" to codexHome.toString(),
                    "CODEX_EXECUTABLE" to codex.toString(),
                    "BROKER_SERVICE_IDENTITY" to identity,
                    "BROKER_READINESS_FILE" to readiness.toString(),
                    "PATH" to "${codex.parent}:/usr/bin:/bin",
                ),
                InstalledProcessExecutor(kast, codex, rejectKastQualification = true),
                EchoCodexLauncher(),
            ) as InstalledBrokerServerConfiguration.Configured

            assertEquals(
                InstalledBrokerServerStart.Rejected(
                    InstalledBrokerServerFailure.KAST_QUALIFICATION_REJECTED,
                ),
                InstalledBrokerServer.start(configuration.options),
            )
            val state = BROKER_SERVICE_STATE_JSON.decodeFromString(
                BrokerServiceStateDocument.serializer(),
                Files.readString(readiness),
            ) as BrokerServiceStateDocument.Rejected
            assertEquals(BROKER_SERVICE_STATE_SCHEMA_VERSION, state.schemaVersion)
            assertEquals(identity, state.serviceIdentity)
            assertEquals(BrokerServerFailure.KAST_QUALIFICATION_REJECTED, state.failure)
        } finally {
            retireOwnedTree(codexHome)
        }
    }

    @Test
    fun `Codex qualification rejection emits its exact bounded startup reason`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val codexHome = Path.of("/private/tmp/kast-service-observed-$suffix")
        Files.createDirectory(codexHome)
        val userHome = temporary.toRealPath()
        val kast = executable(userHome.resolve("kast"))
        val codex = executable(userHome.resolve("codex"))
        val output = ByteArrayOutputStream()
        try {
            val configuration = InstalledBrokerServerConfiguration.admit(
                kast,
                userHome,
                mapOf(
                    "CODEX_HOME" to codexHome.toString(),
                    "CODEX_EXECUTABLE" to codex.toString(),
                    "PATH" to "${codex.parent}:/usr/bin:/bin",
                ),
                InstalledProcessExecutor(kast, codex, rejectCodexQualification = true),
                EchoCodexLauncher(),
            ) as InstalledBrokerServerConfiguration.Configured

            assertEquals(
                InstalledBrokerServerStart.Rejected(
                    InstalledBrokerServerFailure.CODEX_QUALIFICATION_REJECTED,
                ),
                InstalledBrokerServer.start(
                    configuration.options.copy(
                        startupActivitySink = JsonLineBrokerStartupActivitySink(
                            PrintStream(output, true, Charsets.UTF_8),
                        ),
                    ),
                ),
            )
            assertEquals(
                """{"component":"kast-broker","event":"broker-startup-stage","stage":"codex-qualification","outcome":"rejected","reason":"codex-schema-generation-rejected"}""",
                output.toString(Charsets.UTF_8).lineSequence().last { it.isNotBlank() },
            )
        } finally {
            retireOwnedTree(codexHome)
        }
    }

    @Test
    fun `installed composition publishes readiness and transparently serves the Codex socket`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val codexHome = Path.of("/private/tmp/kast-service-$suffix")
        Files.createDirectory(codexHome)
        val userHome = temporary.toRealPath()
        val kast = executable(userHome.resolve("kast"))
        val codex = executable(userHome.resolve("codex"))
        val readiness = codexHome.resolve("broker/service-readiness.json")
        val identity = "sha256:${"a".repeat(64)}"
        val executor = InstalledProcessExecutor(kast, codex)
        val launcher = EchoCodexLauncher()
        val environment = mapOf(
            "CODEX_HOME" to codexHome.toString(),
            "CODEX_EXECUTABLE" to codex.toString(),
            "BROKER_SERVICE_IDENTITY" to identity,
            "BROKER_READINESS_FILE" to readiness.toString(),
            "PATH" to "${codex.parent}:/usr/bin:/bin",
        )
        val client = HttpClient(CIO) { install(WebSockets) }
        var running: InstalledBrokerServer? = null
        try {
            val configuration = InstalledBrokerServerConfiguration.admit(
                kast,
                userHome,
                environment,
                executor,
                launcher,
            ) as InstalledBrokerServerConfiguration.Configured
            running = (
                InstalledBrokerServer.start(configuration.options) as InstalledBrokerServerStart.Started
            ).server

            val readinessText = Files.readString(readiness)
            assertTrue(readinessText.contains("\"serviceIdentity\":\"$identity\""))
            assertTrue(readinessText.contains("\"brokerVersion\":\"$VENDORED_BROKER_VERSION\""))
            val initialize =
                """{"id":0,"method":"initialize","params":{"clientInfo":{"name":"test"}}}"""
            val request = " { \"id\" : 1, \"method\" : \"model/list\", \"params\" : {} } "
            client.webSocket({
                url("ws://localhost/rpc")
                unixSocket(configuration.options.publicSocket.path.toString())
            }) {
                send(initialize)
                assertEquals(
                    """{"id":0,"result":{}}""",
                    (incoming.receive() as Frame.Text).readText(),
                )
                send(request)
                assertEquals(request, (incoming.receive() as Frame.Text).readText())
                send(
                    """{"id":2,"method":"thread/start","params":{"cwd":"$userHome","dynamicTools":[]}}""",
                )
                val bootstrapped = Json.parseToJsonElement(
                    (incoming.receive() as Frame.Text).readText(),
                ).jsonObject.getValue("params").jsonObject
                assertTrue(
                    bootstrapped.getValue("developerInstructions").jsonPrimitive.content
                        .contains("Kast provides compiler-grounded Kotlin source intelligence"),
                )
                val kastNamespace = bootstrapped.getValue("dynamicTools").jsonArray
                    .map { it.jsonObject }
                    .single { it.getValue("name").jsonPrimitive.content == "kast" }
                assertEquals(
                    listOf(
                        "query",
                        "source_read",
                        "semantic_query",
                        "impact_analyze",
                        "diagnostic_check",
                        "change_plan",
                        "change_apply",
                        "change_recover",
                    ),
                    kastNamespace.getValue("tools").jsonArray.map { tool ->
                        tool.jsonObject.getValue("name").jsonPrimitive.content
                    },
                )
            }

            launcher.terminateUnexpectedly()
            assertEquals(
                InstalledBrokerServerTermination.UPSTREAM_EXITED,
                withTimeout(2_000) { running.awaitTermination() },
            )
            assertFalse(Files.exists(readiness))
            assertFalse(Files.exists(configuration.options.publicSocket.path))
        } finally {
            client.close()
            running?.close()
            assertFalse(Files.exists(readiness))
            retireOwnedTree(codexHome)
        }
    }

    private inner class InstalledProcessExecutor(
        private val kast: Path,
        private val codex: Path,
        private val rejectKastQualification: Boolean = false,
        private val rejectCodexQualification: Boolean = false,
    ) : BrokerProcessExecutor {
        override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution = when {
            request.executable.path == kast && request.arguments == listOf("--version") ->
                if (rejectKastQualification) {
                    BrokerProcessExecution.Completed(1, "", "rejected")
                } else {
                    BrokerProcessExecution.Completed(0, "kast 9.9.9\n", "")
                }
            request.executable.path == kast && request.arguments == listOf("--schema") ->
                BrokerProcessExecution.Completed(0, kastSchema(), "")
            request.executable.path == codex && request.arguments == listOf("--version") ->
                BrokerProcessExecution.Completed(0, "codex-cli 9.9.9\n", "")
            request.executable.path == codex &&
                request.arguments.take(4) == listOf(
                    "app-server",
                    "generate-json-schema",
                    "--experimental",
                    "--out",
                ) -> {
                if (rejectCodexQualification) {
                    return BrokerProcessExecution.Completed(1, "", "rejected")
                }
                val output = Path.of(request.arguments.last())
                CodexOwnedSchema.entries.forEach { schema ->
                    Files.writeString(output.resolve(schema.fileName), """{"type":"object"}""")
                }
                BrokerProcessExecution.Completed(0, "", "")
            }
            else -> BrokerProcessExecution.Completed(1, "", "unexpected invocation")
        }
    }

    private class EchoCodexLauncher : CodexAppServerProcessLauncher {
        private val alive = AtomicBoolean(false)
        private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null

        override suspend fun launch(
            request: CodexAppServerProcessRequest,
        ): CodexAppServerProcessAdmission {
            val startedEngine = embeddedServer(
                factory = io.ktor.server.cio.CIO,
                configure = { unixConnector(request.socket.toString()) },
                module = {
                    install(ServerWebSockets)
                    routing {
                        webSocket("/") {
                            for (frame in incoming) {
                                val text = (frame as? Frame.Text)?.readText() ?: break
                                if (text.contains("\"method\":\"initialize\"")) {
                                    send("""{"id":0,"result":{}}""")
                                } else {
                                    send(text)
                                }
                            }
                        }
                    }
                },
            )
            startedEngine.startSuspend(wait = false)
            engine = startedEngine
            alive.set(true)
            return CodexAppServerProcessAdmission.Started(
                object : CodexAppServerProcess {
                    override val pid: Long = 4321
                    override fun isAlive(): Boolean = alive.get()
                    override suspend fun close() = stop()
                },
            )
        }

        suspend fun terminateUnexpectedly() = stop()

        private suspend fun stop() {
            if (!alive.compareAndSet(true, false)) return
            engine?.stopSuspend(0, 1_000)
        }
    }

    private fun kastSchema(): String {
        val factory = when (
            val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        ) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error(construction.failures)
        }
        return when (
            val construction = installedSchema("{}", "{}", factory.surface)
        ) {
            is InstalledSchemaConstruction.Constructed -> construction.document.value
            is InstalledSchemaConstruction.Rejected -> error(construction.failure)
        }
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path.toRealPath()
    }

    private fun retireOwnedTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
