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
    fun `management status works without admitting optional Codex host`() = withPayload { root, kast ->
        runBlocking {
            val activities = java.util.concurrent.CopyOnWriteArrayList<BrokerStartupActivity>()
            val options =
                (InstalledCoordinatorConfiguration.admit(
                        kast,
                        root,
                        mapOf("PATH" to "/usr/bin:/bin"),
                        BrokerStartupActivitySink {
                            activities += it
                            BrokerStartupActivityPublication.PUBLISHED
                        },
                    ) as Refinement.Refined)
                    .value
            val running = (InstalledCoordinator.start(options) as InstalledCoordinatorStart.Started).coordinator
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                withTimeout(5_000) {
                    client.webSocket({
                        url("ws://localhost${DaemonManagementProtocol.route}")
                        unixSocket(options.socket.path.toString())
                    }) {
                        send(
                            DaemonManagementProtocol.json.encodeToString(
                                DaemonManagementRequest.serializer(),
                                DaemonManagementRequest.Status(),
                            )
                        )
                        val reply =
                            DaemonManagementProtocol.json.decodeFromString<DaemonManagementResponse>(
                                (incoming.receive() as Frame.Text).readText()
                            )
                        assertTrue(reply is DaemonManagementResponse.Status, reply.toString())
                        assertEquals(
                            CoordinatorHostAttachment.PENDING,
                            (reply as DaemonManagementResponse.Status).coordinator.hostAttachment,
                        )
                    }
                }
                assertFalse(activities.any { it.stage == BrokerStartupStage.HOST_ADMISSION })
                assertFalse(Files.exists(root.resolve("state/run/u.sock")))
            } finally {
                client.close()
                running.close()
            }
        }
    }

    @Test
    fun `default coordinator serves status alongside a live native Codex endpoint`() = withPayload { root, kast ->
        runBlocking {
            val native = root.resolve(".codex/app-server-control/app-server-control.sock")
            Files.createDirectories(native.parent)
            java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { incumbent ->
                incumbent.bind(java.net.UnixDomainSocketAddress.of(native))
                val inode = Files.getAttribute(native, "unix:ino")
                val options =
                    (InstalledCoordinatorConfiguration.admit(kast, root, emptyMap()) as Refinement.Refined).value
                val start = InstalledCoordinator.start(options)
                assertTrue(start is InstalledCoordinatorStart.Started, start.toString())
                val running = (start as InstalledCoordinatorStart.Started).coordinator
                try {
                    assertNotEquals(native, options.socket.path)
                    assertEquals(BrokerSocketReachability.REACHABLE, JdkBrokerSocketProbe.probe(options.socket.path))
                    assertTrue(incumbent.isOpen)
                    assertEquals(inode, Files.getAttribute(native, "unix:ino"))
                } finally {
                    running.close()
                }
                assertEquals(inode, Files.getAttribute(native, "unix:ino"))
            }
        }
    }

    @Test
    fun `canonical service cannot publish readiness without native Codex protocol`() = withPayload { root, kast ->
        runBlocking {
            val activities = java.util.concurrent.CopyOnWriteArrayList<BrokerStartupActivity>()
            val sink = BrokerStartupActivitySink {
                activities += it
                BrokerStartupActivityPublication.PUBLISHED
            }
            val options =
                (InstalledCoordinatorConfiguration.admit(
                        kast,
                        root,
                        mapOf("PATH" to "/usr/bin:/bin", "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "codex-control"),
                        sink,
                    ) as Refinement.Refined)
                    .value
            val started = InstalledCoordinator.start(options)
            try {
                assertTrue(started is InstalledCoordinatorStart.Rejected)
                assertFalse(
                    activities.contains(BrokerStartupActivity.Completed(BrokerStartupStage.READINESS_PUBLICATION))
                )
                assertFalse(Files.exists(options.socket.path))
            } finally {
                if (started is InstalledCoordinatorStart.Started) started.coordinator.close()
            }
        }
    }

    @Test
    fun `coordinator admission does not require a Codex executable`() = withPayload { root, kast ->
        assertTrue(
            InstalledCoordinatorConfiguration.admit(kast, root, mapOf("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private"))
                is Refinement.Refined,
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
        val demand =
            io.github.amichne.kast.appserver.runtime.WorkspaceDemand { _, _ -> error("unexpected semantic demand") }
        val host = coordinator.admitHost(demand)
        assertTrue(host is InstalledBrokerServerConfiguration.Configured, host.toString())
        val prepared = (host as InstalledBrokerServerConfiguration.Configured).options
        assertSame(demand, prepared.kastOptions.workspaceDemand)
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
                    (InstalledCoordinatorConfiguration.admit(
                            kast,
                            root,
                            mapOf("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private"),
                            sink,
                        ) as Refinement.Refined)
                        .value
                val start = InstalledCoordinator.start(options)
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

    @Test
    fun `management refuses stale generation and stopped service before registry writes`() = withPayload { root, kast ->
        runBlocking {
            val options = (InstalledCoordinatorConfiguration.admit(kast, root, emptyMap()) as Refinement.Refined).value
            val running = (InstalledCoordinator.start(options) as InstalledCoordinatorStart.Started).coordinator
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                suspend fun exchange(request: DaemonManagementRequest): DaemonManagementResponse {
                    var result: DaemonManagementResponse? = null
                    withTimeout(5_000) {
                        client.webSocket({
                            url("ws://localhost${DaemonManagementProtocol.route}")
                            unixSocket(options.socket.path.toString())
                        }) {
                            send(
                                DaemonManagementProtocol.json.encodeToString(
                                    DaemonManagementRequest.serializer(),
                                    request,
                                )
                            )
                            result =
                                DaemonManagementProtocol.json.decodeFromString<DaemonManagementResponse>(
                                    (incoming.receive() as Frame.Text).readText()
                                )
                        }
                    }
                    return checkNotNull(result)
                }
                val observed =
                    (exchange(DaemonManagementRequest.Status()) as DaemonManagementResponse.Status).coordinator
                val target =
                    DaemonManagementTarget(
                        observed.installationId,
                        observed.stateEpoch,
                        observed.serviceGeneration,
                        observed.configurationIdentity,
                    )
                val workspace = Files.createDirectory(root.resolve("workspace"))
                assertEquals(
                    DaemonManagementResponse.Rejected(
                        DaemonManagementRejection.Protocol(DaemonManagementFailure.IDENTITY_REJECTED)
                    ),
                    exchange(
                        DaemonManagementRequest.RegisterWorkspace(
                            target.copy(serviceGeneration = "stale"),
                            workspace.toString(),
                        )
                    ),
                )
                assertFalse(Files.exists(root.resolve("config/workspaces.json")))
                Files.writeString(options.serviceDirectory.resolve("stopped"), "")
                assertEquals(
                    DaemonManagementResponse.Rejected(
                        DaemonManagementRejection.Protocol(DaemonManagementFailure.LIFECYCLE_TRANSITION)
                    ),
                    exchange(DaemonManagementRequest.RegisterWorkspace(target, workspace.toString())),
                )
                assertEquals(
                    DaemonManagementResponse.Rejected(
                        DaemonManagementRejection.Protocol(DaemonManagementFailure.LIFECYCLE_TRANSITION)
                    ),
                    exchange(DaemonManagementRequest.Status()),
                )
                assertFalse(Files.exists(root.resolve("config/workspaces.json")))
            } finally {
                client.close()
                running.close()
            }
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
