package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCapabilityName
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstallationLifecycleCapabilityTest {
    @Test
    fun `normal stop uses the advertised stop capability`() {
        exchangeFixture(IdeLifecycleCommand.StopHost("host", "update"), MANAGEMENT, effectExpected = true)
    }

    @Test
    fun `settlement uses the advertised settlement capability`() {
        exchangeFixture(IdeLifecycleCommand.SettleHost("host", "update"), MANAGEMENT, effectExpected = true)
    }

    @Test
    fun `a Host without the stop capability rejects stop before the effect`() {
        exchangeFixture(
            IdeLifecycleCommand.StopHost("host", "update"),
            MANAGEMENT - IdeLifecycleCapabilityName.HOST_NORMAL_STOP,
            effectExpected = false,
        )
    }

    @Test
    fun `workspace inspection admits a Host without graphical presentation`() {
        workspaceFixture(present = false)
    }

    @Test
    fun `graphical presentation rejects a Host without that capability`() {
        workspaceFixture(present = true)
    }

    private fun workspaceFixture(present: Boolean) {
        val root = Files.createTempDirectory(Path.of("/tmp"), "k").toRealPath()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val fixture = ManagedDeploymentFixture(root)
            val inspected =
                inspected(fixture.idea, fixture.identity(), MANAGEMENT)
                    .copy(
                        compatibility =
                            HostedCompatibilityDocument(
                                "262.10315.125",
                                "262.10315.125-IJ",
                                "0.0.0",
                                io.github.amichne.kast.protocol.wire.CanonicalHostedContract.document,
                            ),
                        capabilities = IdeLifecycleCapabilityName.entries.toSet() - IdeLifecycleCapabilityName.PRESENT,
                    )
            val socket =
                io.github.amichne.kast.distribution.contract.HostedEndpointPaths.directory(
                        fixture.home,
                        io.github.amichne.kast.distribution.contract.HostedEndpointPaths.Family.LIFECYCLE,
                        fixture.idea.toString(),
                    )
                    .resolve("host.sock")
            Files.createDirectories(socket.parent)
            server.bind(UnixDomainSocketAddress.of(socket))
            val serving = executor.submit {
                serve(server, listOf(IdeLifecycleCommand.Inspect to inspected), Json { encodeDefaults = true })
            }
            val request =
                if (present)
                    io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.Present(
                        target = io.github.amichne.kast.protocol.contract.IdeProjectTarget("host", "project", "/work"),
                        requestId = "request",
                    )
                else io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.Inspect
            val result = IdeLifecycleClient(fixture.home, fixture.idea, fixture.deployment).execute(request, "test")
            assertEquals(
                if (present) IdeLifecycleResult.Blocked(IdeLifecycleFailure.CAPABILITY_MISMATCH) else inspected,
                result,
            )
            serving.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
            cleanupHostedEndpointFixture(root.resolve("state/home"))
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun exchangeFixture(
        command: IdeLifecycleCommand,
        capabilities: Set<IdeLifecycleCapabilityName>,
        effectExpected: Boolean,
    ) {
        val root = Files.createTempDirectory(Path.of("/tmp"), "k").toRealPath()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val fixture = ManagedDeploymentFixture(root)
            val home = fixture.idea
            val identity = fixture.identity()
            val inspected = inspected(home, identity, capabilities)
            val response =
                when (command) {
                    is IdeLifecycleCommand.StopHost -> IdeLifecycleResult.HostStopRequested("host", "update", identity)
                    is IdeLifecycleCommand.SettleHost -> IdeLifecycleResult.HostSettled("host", "update", identity)
                    else -> error("unexpected management command: $command")
                }
            val socket =
                io.github.amichne.kast.distribution.contract.HostedEndpointPaths.directory(
                        fixture.home,
                        io.github.amichne.kast.distribution.contract.HostedEndpointPaths.Family.LIFECYCLE,
                        home.toString(),
                    )
                    .resolve("host.sock")
            Files.createDirectories(socket.parent)
            server.bind(UnixDomainSocketAddress.of(socket))
            val json = Json { encodeDefaults = true }
            val expected =
                if (effectExpected) listOf(IdeLifecycleCommand.Inspect, command)
                else listOf(IdeLifecycleCommand.Inspect)
            val script = expected.mapIndexed { index, request -> request to if (index == 0) inspected else response }
            val serving = executor.submit { serve(server, script, json) }
            val actual = IdeLifecycleClient(fixture.home, home, fixture.deployment).manageInstallation(command)
            assertEquals(
                if (effectExpected) response else IdeLifecycleResult.Blocked(IdeLifecycleFailure.CAPABILITY_MISMATCH),
                actual,
            )
            serving.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
            cleanupHostedEndpointFixture(root.resolve("state/home"))
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun inspected(
        home: Path,
        identity: IdeHostRuntimeIdentity,
        capabilities: Set<IdeLifecycleCapabilityName>,
    ): IdeLifecycleResult.Inspected {
        val compatibility =
            HostedCompatibilityDocument(
                "262.1",
                "262.1-IJ",
                "1.2.3",
                HostedContractDocument(
                    runtimeProtocolIdentity = "prior-contract",
                    operationRegistryDigest = "sha256:" + "c".repeat(64),
                    wireSchemaDigest = "sha256:" + "d".repeat(64),
                    capabilities = listOf("prior-capability"),
                ),
            )
        return IdeLifecycleResult.Inspected(
            "host",
            home.toString(),
            "IU-262.1",
            emptyList(),
            compatibility,
            capabilities = capabilities,
            runtimeIdentity = identity,
        )
    }

    private fun serve(
        server: ServerSocketChannel,
        script: List<Pair<IdeLifecycleCommand, IdeLifecycleResult>>,
        json: Json,
    ) {
        for ((request, response) in script) {
            server.accept().use { channel ->
                val input = DataInputStream(Channels.newInputStream(channel))
                val length = input.readInt()
                check(length in 1..65_536)
                val actual =
                    json.decodeFromString(
                        IdeLifecycleCommand.serializer(),
                        input.readNBytes(length).decodeToString(),
                    )
                assertEquals(request, actual)
                val bytes = json.encodeToString<IdeLifecycleResult>(response).toByteArray()
                DataOutputStream(Channels.newOutputStream(channel)).apply {
                    writeInt(bytes.size)
                    write(bytes)
                    flush()
                }
            }
        }
    }

    private companion object {
        val MANAGEMENT =
            setOf(
                IdeLifecycleCapabilityName.INSPECT,
                IdeLifecycleCapabilityName.HOST_NORMAL_STOP,
                IdeLifecycleCapabilityName.HOST_SETTLEMENT,
            )
    }
}
