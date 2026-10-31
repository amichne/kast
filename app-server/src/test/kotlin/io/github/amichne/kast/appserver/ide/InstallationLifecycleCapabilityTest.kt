package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
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
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstallationLifecycleCapabilityTest {
    @Test
    fun `prior stop capability remains enough after the installer learns refresh`() {
        exchangeFixture(IdeLifecycleCommand.StopHost("host", "update"), refresh = false, effectExpected = true)
    }

    @Test
    fun `refresh refuses a prior Host with no actual operation capability`() {
        exchangeFixture(
            IdeLifecycleCommand.RefreshHostBundle("host", "update"),
            refresh = false,
            effectExpected = false,
        )
    }

    @Test
    fun `refresh uses the advertised operation and preserves the acknowledgment identity`() {
        exchangeFixture(IdeLifecycleCommand.RefreshHostBundle("host", "update"), refresh = true, effectExpected = true)
    }

    private fun exchangeFixture(command: IdeLifecycleCommand, refresh: Boolean, effectExpected: Boolean) {
        val root = Files.createTempDirectory(Path.of("/tmp"), "k").toRealPath()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val home = Files.createDirectory(root.resolve("idea"))
            val identity = identity(home, root)
            val inspected = inspected(home, identity, refresh)
            val response =
                if (command is IdeLifecycleCommand.StopHost)
                    IdeLifecycleResult.HostStopRequested("host", "update", identity)
                else IdeLifecycleResult.HostRefreshRequested("host", "update", identity)
            val digest =
                MessageDigest.getInstance("SHA-256").digest(home.toString().toByteArray()).take(16).joinToString("") {
                    "%02x".format(it)
                }
            val socket = root.resolve(".kast/ide-lifecycle/$digest/host.sock")
            Files.createDirectories(socket.parent)
            server.bind(UnixDomainSocketAddress.of(socket))
            val json = Json { encodeDefaults = true }
            val expected =
                if (effectExpected) listOf(IdeLifecycleCommand.Inspect, command)
                else listOf(IdeLifecycleCommand.Inspect)
            val script = expected.mapIndexed { index, request -> request to if (index == 0) inspected else response }
            val serving = executor.submit { serve(server, script, json) }
            val actual = IdeLifecycleClient(root, home).manageInstallation(command)
            assertEquals(
                if (effectExpected) response else IdeLifecycleResult.Blocked(IdeLifecycleFailure.CAPABILITY_MISMATCH),
                actual,
            )
            serving.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun identity(home: Path, root: Path): IdeHostRuntimeIdentity =
        IdeHostRuntimeIdentity(
            home.toString(),
            root.resolve("profile").toString(),
            42,
            100,
            "host",
            IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
            io.github.amichne.kast.protocol.contract.HostAutoReload.ENABLED,
        )

    private fun inspected(
        home: Path,
        identity: IdeHostRuntimeIdentity,
        refresh: Boolean,
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
        val capabilities =
            setOf(IdeLifecycleCapabilityName.INSPECT, IdeLifecycleCapabilityName.HOST_NORMAL_STOP) +
                if (refresh) setOf(IdeLifecycleCapabilityName.HOST_RELOAD_REFRESH) else emptySet()
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
}
