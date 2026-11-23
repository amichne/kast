package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.runtime.BrokerControlRoute
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import io.github.amichne.kast.appserver.runtime.connectCodexUnixWebSocket
import io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_FENCE
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledCoordinatorEndpointTest {
    @Test
    fun `private coordinator rejects unsupported semantic protocol before workspace admission`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                val options = privateOptions(kast, root)
                val started = InstalledCoordinator.start(options) as InstalledCoordinatorStart.Started
                try {
                    val target = semanticTarget(options.socket.path)
                    val request =
                        DaemonOperationRequest(
                            target,
                            root.resolve("missing-workspace").toString(),
                            DaemonOperationSelection.PublicTool(DaemonOperationTool.QUERY_SYMBOLS, JsonNull),
                            version = 0,
                        )
                    val result = semanticExchange(options.socket.path, request)
                    assertEquals(
                        DaemonOperationResponse.Rejected(
                            DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.UNSUPPORTED_VERSION)
                        ),
                        result,
                    )
                } finally {
                    started.coordinator.close()
                }
            }
        }

    @Test
    fun `semantic route invokes the admitted tool once and rejects a stale service generation`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                Files.writeString(root.resolve("settings.gradle.kts"), "")
                val options = privateOptions(kast, root)
                val invocations = AtomicInteger()
                val document = JsonPrimitive("semantic fixture response")
                val started =
                    InstalledCoordinator.start(
                        options,
                        semantic = { workspace, operation ->
                            invocations.incrementAndGet()
                            assertEquals(root, workspace.path)
                            val tool = (operation as DaemonOperationCall.PublicTool).tool
                            assertEquals(PublicToolIdentity.QUERY_SYMBOLS, tool.identity)
                            assertEquals(PublicToolCatalog.COMMON, tool.catalog)
                            DaemonOperationResult.Complete(
                                CanonicalJsonDocument.generated(JsonElement.serializer()).create(document)
                            )
                        },
                    ) as InstalledCoordinatorStart.Started
                try {
                    val target = semanticTarget(options.socket.path)
                    val stale = target.copy(serviceGeneration = "00000000-0000-0000-0000-000000000001")
                    assertEquals(
                        DaemonOperationResponse.Rejected(
                            DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.IDENTITY_REJECTED)
                        ),
                        semanticExchange(options.socket.path, semanticQuery(stale, root)),
                    )
                    assertEquals(0, invocations.get())
                    val selection = semanticQuery(target, root).selection as DaemonOperationSelection.PublicTool
                    val tool =
                        (PublicToolContract.admit(selection.tool.identity, selection.arguments, selection.catalog)
                                as Refinement.Refined)
                            .value
                    val workspace =
                        (FilesystemCanonicalRootDiscovery.discover(root) as CanonicalRootDiscovery.Discovered).root
                    val reply =
                        InstalledDaemonOperationClient(options.socket.path, target)
                            .exchange(workspace, DaemonOperationCall.PublicTool(tool)) as DaemonOperationResult.Complete
                    assertEquals(document.toString(), reply.document.value)
                    assertEquals(1, invocations.get())
                } finally {
                    started.coordinator.close()
                }
            }
        }

    @Test
    fun `semantic client retains an unobserved mutation outcome without replay`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                Files.writeString(root.resolve("settings.gradle.kts"), "")
                val options = privateOptions(kast, root)
                val workspace =
                    (FilesystemCanonicalRootDiscovery.discover(root) as CanonicalRootDiscovery.Discovered).root
                val plan = (ProtocolText.parse("plan:${"a".repeat(64)}") as Refinement.Refined).value
                val mutation = DaemonOperationCall.Change(DaemonChangeAction.Apply(ChangeApplyRequest(plan)))
                val executions = AtomicInteger()
                val started =
                    InstalledCoordinator.start(
                        options,
                        semantic = { _, operation ->
                            assertEquals(mutation, operation)
                            executions.incrementAndGet()
                            throw CancellationException("fixture drops the reply after dispatch")
                        },
                    ) as InstalledCoordinatorStart.Started
                try {
                    val client =
                        InstalledDaemonOperationClient(options.socket.path, semanticTarget(options.socket.path))
                    assertEquals(
                        DaemonOperationResult.Rejected(
                            DaemonOperationClientRejection.Transport(DaemonOperationClientFailure.OUTCOME_UNOBSERVED)
                        ),
                        withTimeout(5_000) { client.exchange(workspace, mutation) },
                    )
                    assertEquals(1, executions.get())
                } finally {
                    started.coordinator.close()
                }
            }
        }

    @Test
    fun `closing the semantic socket cancels its admitted execution`() = withCoordinatorPayload { root, kast ->
        runBlocking {
            Files.writeString(root.resolve("settings.gradle.kts"), "")
            val options = privateOptions(kast, root)
            val admitted = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val started =
                InstalledCoordinator.start(
                    options,
                    semantic = { _, _ ->
                        admitted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                ) as InstalledCoordinatorStart.Started
            try {
                val request = semanticQuery(semanticTarget(options.socket.path), root)
                val connected =
                    connectCodexUnixWebSocket(
                        options.socket.path,
                        DaemonOperationProtocol.maximumResponseBytes,
                        5_000,
                        BrokerControlRoute.SEMANTIC,
                    )
                        as BrokerUpstreamConnectionAdmission.Connected
                try {
                    assertEquals(
                        BrokerUpstreamSend.SENT,
                        connected.connection.send(DaemonOperationProtocol.json.encodeToString(request)),
                    )
                    withTimeout(5_000) { admitted.await() }
                } finally {
                    connected.connection.close()
                }
                withTimeout(5_000) { cancelled.await() }
            } finally {
                started.coordinator.close()
            }
        }
    }

    private fun semanticQuery(target: DaemonManagementTarget, root: Path): DaemonOperationRequest =
        DaemonOperationRequest(
            target,
            root.toString(),
            DaemonOperationSelection.PublicTool(
                DaemonOperationTool.QUERY_SYMBOLS,
                PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS, PublicToolCatalog.COMMON)
                    .examples
                    .getValue("scopedInterfaceDeclaration")
                    .value,
                PublicToolCatalog.COMMON,
            ),
        )

    private suspend fun semanticTarget(socket: Path): DaemonManagementTarget {
        val connected =
            connectCodexUnixWebSocket(
                socket,
                CoordinatorStatusProtocol.maximumMessageBytes,
                5_000,
                BrokerControlRoute.RUNTIME,
            )
                as BrokerUpstreamConnectionAdmission.Connected
        return try {
            connected.connection.send(Json.encodeToString(CoordinatorControlRequest(CoordinatorControlAction.STATUS)))
            val frame = withTimeout(5_000) { connected.connection.receive() } as BrokerUpstreamFrame.Text
            val snapshot = (CoordinatorStatusSnapshot.decode(frame.message) as Refinement.Refined).value
            DaemonManagementTarget.from(snapshot)
        } finally {
            connected.connection.close()
        }
    }

    private suspend fun semanticExchange(socket: Path, request: DaemonOperationRequest): DaemonOperationResponse =
        HttpClient(CIO) { install(WebSockets) }
            .use { client ->
                var reply: DaemonOperationResponse? = null
                withTimeout(5_000) {
                    client.webSocket({
                        url("ws://localhost/kast-semantic")
                        unixSocket(socket.toString())
                    }) {
                        send(DaemonOperationProtocol.json.encodeToString(request))
                        val frame = incoming.receive() as Frame.Text
                        reply = DaemonOperationProtocol.json.decodeFromString(frame.readText())
                    }
                }
                checkNotNull(reply)
            }

    @Test
    fun `default private coordinator serves status alongside a live native Codex endpoint`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                val native = root.resolve(".codex/app-server-control/app-server-control.sock")
                Files.createDirectories(native.parent)
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { incumbent ->
                    incumbent.bind(java.net.UnixDomainSocketAddress.of(native))
                    val inode = Files.getAttribute(native, "unix:ino")
                    val options = privateOptions(kast, root)
                    val start = InstalledCoordinator.start(options)
                    assertTrue(start is InstalledCoordinatorStart.Started, start.toString())
                    val running = (start as InstalledCoordinatorStart.Started).coordinator
                    try {
                        assertNotEquals(native, options.socket.path)
                        assertEquals(
                            BrokerSocketReachability.REACHABLE,
                            JdkBrokerSocketProbe.probe(options.socket.path),
                        )
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
    fun `canonical service cannot publish readiness without native Codex protocol`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                val activities = java.util.concurrent.CopyOnWriteArrayList<BrokerStartupActivity>()
                val sink = BrokerStartupActivitySink { activity ->
                    activities += activity
                    BrokerStartupActivityPublication.PUBLISHED
                }
                val options = managedOptions(kast, root, sink)
                val discovery =
                    DesktopDaemonDiscovery(
                        object : DesktopDaemonEnvironment {
                            override fun readHome(): DesktopDaemonHomeRead =
                                throw AssertionError("Unexpected home observation before native readiness")

                            override fun read(): DesktopDaemonEnvironmentRead =
                                throw AssertionError("Unexpected discovery observation before native readiness")

                            override fun enable(): DesktopDiscoveryOutcome =
                                throw AssertionError("Unexpected discovery publication before native readiness")

                            override fun remove(): DesktopDiscoveryOutcome =
                                throw AssertionError("Unexpected discovery cleanup")
                        }
                    )
                val started = InstalledCoordinator.start(options, discovery)
                try {
                    assertEquals(
                        InstalledCoordinatorStart.Rejected(BrokerServerFailure.NATIVE_PROTOCOL_REJECTED),
                        started,
                    )
                    assertFalse(activities.any { it.stage == BrokerStartupStage.DESKTOP_DISCOVERY })
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
    fun `semantic execution requires the installation private endpoint`() = withCoordinatorPayload { root, kast ->
        runBlocking {
            val options = managedOptions(kast, root) { error("Unexpected startup activity") }
            assertEquals(
                InstalledCoordinatorStart.Rejected(BrokerServerFailure.PROTOCOL_CONFIGURATION_REJECTED),
                InstalledCoordinator.start(options, semantic = { _, _ -> error("Unexpected semantic execution") }),
            )
            assertFalse(Files.exists(options.socket.path))
        }
    }

    private fun managedOptions(kast: Path, root: Path, sink: BrokerStartupActivitySink): InstalledCoordinatorOptions {
        val readiness =
            BrokerInstallationLayout.from(kast, root.resolve(".codex")).broker.resolve("service-readiness.json")
        val environment =
            mapOf(
                "PATH" to "/usr/bin:/bin",
                "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "codex-control",
                "BROKER_SERVICE_IDENTITY" to "sha256:${"b".repeat(64)}",
                "BROKER_READINESS_FILE" to readiness.toString(),
            )
        return (InstalledCoordinatorConfiguration.admit(
                kast = kast,
                user = root,
                environment = environment,
                activitySink = sink,
            ) as Refinement.Refined)
            .value
    }

    private fun privateOptions(kast: Path, root: Path): InstalledCoordinatorOptions =
        (InstalledCoordinatorConfiguration.admit(kast, root, emptyMap()) as Refinement.Refined).value
}

class InstalledCoordinatorMutationProjectionTest {
    @Test
    fun `semantic client admits the coordinator's projected change outcomes once`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                Files.writeString(root.resolve("settings.gradle.kts"), "")
                val options =
                    (InstalledCoordinatorConfiguration.admit(kast, root, emptyMap()) as Refinement.Refined).value
                val workspace =
                    (FilesystemCanonicalRootDiscovery.discover(root) as CanonicalRootDiscovery.Discovered).root
                val plan = (ProtocolText.parse("plan:${"b".repeat(64)}") as Refinement.Refined).value
                val mutation = DaemonOperationCall.Change(DaemonChangeAction.Apply(ChangeApplyRequest(plan)))
                val rejected = JsonPrimitive("host rejected the application")
                val executions = AtomicInteger()
                val started =
                    InstalledCoordinator.start(
                        options,
                        semantic = { _, operation ->
                            assertEquals(mutation, operation)
                            executions.incrementAndGet()
                            DaemonOperationResult.OperationRejected(
                                CanonicalJsonDocument.generated(JsonElement.serializer()).create(rejected)
                            )
                        },
                    ) as InstalledCoordinatorStart.Started
                try {
                    val reply =
                        InstalledDaemonOperationClient(
                                options.socket.path,
                                DaemonManagementTarget.from(
                                    (readCoordinatorStatus(options.socket.path) as Refinement.Refined).value
                                ),
                            )
                            .exchange(workspace, mutation)
                    assertEquals(rejected.toString(), (reply as DaemonOperationResult.OperationRejected).document.value)
                    assertEquals(1, executions.get())
                } finally {
                    started.coordinator.close()
                }
            }
        }
}

internal fun withCoordinatorPayload(test: (Path, Path) -> Unit) = withCoordinatorPayload(nested = false, test)

/** The installation sits one level below a private root, so a test can own its parent shutdown fence. */
internal fun withFenceableCoordinatorPayload(test: (Path, Path) -> Unit) = withCoordinatorPayload(nested = true, test)

/** Writes the parent shutdown fence that every installation lifecycle observer honours. */
internal fun fenceInstallation(root: Path): Path =
    Files.writeString(root.resolveSibling(INSTALLATION_SHUTDOWN_FENCE), "fenced")

private fun withCoordinatorPayload(nested: Boolean, test: (Path, Path) -> Unit) {
    val owned = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kast-c-").toRealPath()
    try {
        val root = if (nested) Files.createDirectory(owned.resolve("i")) else owned
        for (directory in listOf("bin", "lib", "share")) Files.createDirectory(root.resolve(directory))
        val kast = Files.writeString(root.resolve("bin/kast"), "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(kast, PosixFilePermissions.fromString("rwx------"))
        test(root, kast)
    } finally {
        Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
