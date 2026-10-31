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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdeInstallationManagementTest {
    @Test
    fun `installer can resume an exact prior Host with a different semantic contract`() {
        fixture { home, idea, inspected, serve ->
            val command = IdeLifecycleCommand.ResumeHost(inspected.host, "update")
            val expected = IdeLifecycleResult.HostResumed(inspected.host, "update")
            val finished = serve(listOf(IdeLifecycleCommand.Inspect to inspected, command to expected))
            assertEquals(expected, IdeLifecycleClient(home, idea).manageInstallation(command))
            finished()
        }
    }

    @Test
    fun `installer rejects a foreign Host without a second exchange`() {
        fixture { home, idea, inspected, serve ->
            val finished = serve(listOf(IdeLifecycleCommand.Inspect to inspected))
            assertEquals(
                IdeLifecycleResult.Blocked(IdeLifecycleFailure.WRONG_HOST),
                IdeLifecycleClient(home, idea).manageInstallation(IdeLifecycleCommand.ResumeHost("foreign", "update")),
            )
            finished()
        }
    }

    private fun fixture(
        action:
            (
                Path,
                Path,
                IdeLifecycleResult.Inspected,
                (List<Pair<IdeLifecycleCommand, IdeLifecycleResult>>) -> () -> Unit,
            ) -> Unit
    ) {
        val root = Files.createTempDirectory(Path.of("/tmp"), "k").toRealPath()
        val executor = Executors.newSingleThreadExecutor()
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            val idea = Files.createDirectory(root.resolve("idea"))
            val hash =
                MessageDigest.getInstance("SHA-256").digest(idea.toString().toByteArray()).take(16).joinToString("") {
                    "%02x".format(it)
                }
            val directory = Files.createDirectories(root.resolve(".kast/ide-lifecycle/$hash"))
            listener.bind(UnixDomainSocketAddress.of(directory.resolve("host.sock")))
            val inspected = inspected(idea)
            val json = Json { encodeDefaults = true }
            action(root, idea, inspected) { steps ->
                val serving = executor.submit {
                    for ((command, result) in steps) listener.accept().use { channel ->
                        val input = DataInputStream(Channels.newInputStream(channel))
                        val size = input.readInt()
                        val raw = input.readNBytes(size).decodeToString()
                        assertEquals(command, json.decodeFromString<IdeLifecycleCommand>(raw))
                        val encoded = json.encodeToString(IdeLifecycleResult.serializer(), result).toByteArray()
                        DataOutputStream(Channels.newOutputStream(channel)).apply {
                            writeInt(encoded.size)
                            write(encoded)
                            flush()
                        }
                    }
                }
                ({
                    serving.get(5, TimeUnit.SECONDS)
                })
            }
        } finally {
            listener.close()
            executor.shutdownNow()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun inspected(idea: Path): IdeLifecycleResult.Inspected {
        val host = UUID(0, 1).toString()
        return IdeLifecycleResult.Inspected(
            host,
            idea.toString(),
            "262.1.1",
            emptyList(),
            HostedCompatibilityDocument(
                "262.1.1",
                "262.1.1-IJ",
                "1.2.3",
                HostedContractDocument(
                    runtimeProtocolIdentity = "different.runtime.v1",
                    operationRegistryDigest = "sha256:" + "a".repeat(64),
                    wireSchemaDigest = "sha256:" + "b".repeat(64),
                    capabilities = listOf("query.run"),
                ),
            ),
            capabilities = setOf(IdeLifecycleCapabilityName.HOST_SETTLEMENT),
            runtimeIdentity =
                IdeHostRuntimeIdentity(
                    idea.toString(),
                    "/profile",
                    42,
                    100,
                    host,
                    IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
                ),
        )
    }
}
