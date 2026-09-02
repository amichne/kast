package io.github.amichne.kast.cli.broker

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class InstalledBrokerServerTest {
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
                url("ws://localhost/")
                unixSocket(configuration.options.publicSocket.path.toString())
            }) {
                send(initialize)
                assertEquals(
                    """{"id":0,"result":{}}""",
                    (incoming.receive() as Frame.Text).readText(),
                )
                send(request)
                assertEquals(request, (incoming.receive() as Frame.Text).readText())
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

    private fun kastSchema(): String =
        """
        {
          "schemaVersion": 1,
          "serverProjection": {
            "schemaVersion": 2,
            "namespace": "kast",
            "tools": [{
              "operationId": "workspace.inspect",
              "name": "workspace_inspect",
              "description": "Inspect the admitted workspace.",
              "deferLoading": false,
              "approvalPolicy": "none",
              "cliUsage": "kast workspace inspect",
              "inputSchema": {"type":"object","additionalProperties":false,"properties":{}},
              "outputSchema": {"type":"object"},
              "invocation": {"type":"CLI","command":["workspace","inspect"],"bindings":[]}
            }]
          }
        }
        """.trimIndent()

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
