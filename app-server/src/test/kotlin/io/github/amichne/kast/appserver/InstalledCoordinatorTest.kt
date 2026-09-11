package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InstalledCoordinatorTest {
    @Test
    fun `coordinator admission does not require a Codex executable`() = withPayload { root, kast ->
        assertTrue(
            InstalledCoordinatorConfiguration.admit(kast, root, emptyMap()) is Refinement.Refined,
            "workspace coordinator must admit an installed payload before any optional Codex host exists",
        )
        assertFalse(Files.exists(root.resolve(".codex")))
    }

    @Test
    fun `lazy host admission retains coordinator managed service generation`() = withPayload { root, kast ->
        val codex = Files.writeString(root.resolve("codex"), "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(codex, PosixFilePermissions.fromString("rwx------"))
        val codexHome = root.resolve("codex-home")
        val readiness = BrokerInstallationLayout.from(kast, codexHome).broker.resolve("service-readiness.json")
        val environment =
            mapOf(
                "CODEX_HOME" to codexHome.toString(),
                "CODEX_EXECUTABLE" to codex.toString(),
                "BROKER_SERVICE_IDENTITY" to "sha256:${"b".repeat(64)}",
                "BROKER_READINESS_FILE" to readiness.toString(),
                "PATH" to "/usr/bin:/bin",
            )
        val coordinator = (InstalledCoordinatorConfiguration.admit(kast, root, environment) as Refinement.Refined).value
        val host = coordinator.admitHost()
        assertTrue(host is InstalledBrokerServerConfiguration.Configured, host.toString())
        val prepared = (host as InstalledBrokerServerConfiguration.Configured).options
        assertSame(
            coordinator.readiness,
            prepared.readiness,
            "lazy frontend must retain the already published generation instead of minting a second identity",
        )
    }

    @Test
    fun `runtime socket serves qualified status before and after unavailable frontend without launching a host`() =
        withPayload { root, kast ->
            runBlocking {
                val activities = java.util.concurrent.CopyOnWriteArrayList<BrokerStartupActivity>()
                val sink = BrokerStartupActivitySink {
                    activities += it
                    BrokerStartupActivityPublication.PUBLISHED
                }
                val options =
                    (InstalledCoordinatorConfiguration.admit(kast, root, emptyMap(), sink) as Refinement.Refined).value
                val start = InstalledCoordinator.start(options, InstalledWorkerEffects.Unavailable)
                assertTrue(start is InstalledCoordinatorStart.Started, start.toString())
                val running = (start as InstalledCoordinatorStart.Started).coordinator
                val client = HttpClient(CIO) { install(WebSockets) }
                try {
                    suspend fun status(): JsonObject {
                        var reply = ""
                        withTimeout(5_000) {
                            client.webSocket({
                                url("ws://localhost/kast-runtime")
                                unixSocket(options.socket.path.toString())
                            }) {
                                send("""{"action":"STATUS","root":""}""")
                                reply = (incoming.receive() as Frame.Text).readText()
                            }
                        }
                        return Json.parseToJsonElement(reply).jsonObject
                    }
                    val before = status()
                    assertTrue(
                        activities.contains(BrokerStartupActivity.Completed(BrokerStartupStage.READINESS_PUBLICATION))
                    )
                    assertFalse(activities.any { it.stage == BrokerStartupStage.HOST_ADMISSION })
                    assertEquals(BrokerSocketReachability.REACHABLE, JdkBrokerSocketProbe.probe(options.socket.path))
                    assertEquals("READY", before["status"]?.jsonPrimitive?.content)
                    assertTrue(before["installationId"]?.jsonPrimitive?.content?.isNotEmpty() == true)
                    assertFalse(Files.exists(root.resolve(".codex")))
                    assertFalse(Files.exists(root.resolve("state/run/u.sock")))
                    withTimeout(5_000) {
                        client.webSocket({
                            url("ws://localhost/rpc")
                            unixSocket(options.socket.path.toString())
                        }) {
                            assertTrue(incoming.receiveCatching().isClosed)
                        }
                    }
                    val after = status()
                    assertEquals(
                        before.filterKeys { it != "hostAttachment" },
                        after.filterKeys { it != "hostAttachment" },
                        "optional host failure must not replace the coordinator generation",
                    )
                    assertEquals("PENDING", before["hostAttachment"]?.jsonPrimitive?.content)
                    assertEquals("REJECTED", after["hostAttachment"]?.jsonPrimitive?.content)
                    assertTrue(
                        activities.contains(
                            BrokerStartupActivity.Rejected(
                                BrokerStartupStage.HOST_ADMISSION,
                                BrokerStartupRejection.HostAdmission(
                                    InstalledBrokerServerConfigurationFailure.CODEX_EXECUTABLE_REJECTED
                                ),
                            )
                        )
                    )
                    assertFalse(Files.exists(root.resolve(".codex")))
                    assertFalse(Files.exists(root.resolve("state/run/u.sock")))
                } finally {
                    client.close()
                    running.close()
                }
                assertFalse(Files.exists(options.socket.physicalPath))
            }
        }

    private fun withPayload(test: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-c-").toRealPath()
        try {
            for (directory in listOf("bin", "lib", "share")) Files.createDirectory(root.resolve(directory))
            val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
            test(root, kast)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
