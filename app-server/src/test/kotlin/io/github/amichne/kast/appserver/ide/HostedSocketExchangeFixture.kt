package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

internal data class HostedSocketExchangeScript(
    val expected: List<String>,
    val describeMetadata: List<HostedCompatibilityDocument>,
    val omitEvidence: Boolean = false,
    val replaceSocketBeforeDescribeReply: Boolean = false,
)

/** Each exchange owns its temporary root, peer script and executor; no fixture state is shared across cases. */
internal class HostedSocketExchangeFixture {
    private val json = Json { encodeDefaults = true }

    fun exchange(
        expected: List<String>,
        metadata: HostedCompatibilityDocument,
        omitEvidence: Boolean = false,
        replaceSocketBeforeDescribeReply: Boolean = false,
        assert: (ExistingIdeSocketClient, CanonicalRoot) -> Unit,
    ) =
        exchange(
            script =
                HostedSocketExchangeScript(
                    expected = expected,
                    describeMetadata = expected.filter { it == "DESCRIBE" }.map { metadata },
                    omitEvidence = omitEvidence,
                    replaceSocketBeforeDescribeReply = replaceSocketBeforeDescribeReply,
                ),
            assert = assert,
        )

    fun exchange(
        script: HostedSocketExchangeScript,
        clientFactory: (Path) -> ExistingIdeSocketClient = { ExistingIdeSocketClient(it, exchangeMillis = 2_000) },
        assert: (ExistingIdeSocketClient, CanonicalRoot) -> Unit,
    ) {
        val home = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "khx-")
        val executor = Executors.newSingleThreadExecutor()
        val successors = ArrayList<ServerSocketChannel>()
        try {
            Files.writeString(home.resolve("settings.gradle.kts"), "")
            val root = CanonicalRoot(home)
            val peer =
                peerFixture(
                    home = home,
                    metadata = ArrayDeque(script.describeMetadata),
                    omitEvidence = script.omitEvidence,
                    replaceSocket = script.replaceSocketBeforeDescribeReply,
                    successors = successors,
                )
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(peer.socket))
                val worker =
                    executor.submit<List<String>> {
                        script.expected.map { operation ->
                            server.accept().use { channel -> peer.respond(channel, operation) }
                        }
                    }

                assert(clientFactory(home), root)
                assertEquals(script.expected, worker.get(5, TimeUnit.SECONDS))
                assertEquals(emptyList<HostedCompatibilityDocument>(), peer.metadata.toList())
                successors.forEach { assertNull(it.accept(), "unadmitted successor received a connection") }
            }
        } finally {
            executor.shutdownNow()
            successors.forEach { it.close() }
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun peerFixture(
        home: Path,
        metadata: ArrayDeque<HostedCompatibilityDocument>,
        omitEvidence: Boolean,
        replaceSocket: Boolean,
        successors: MutableList<ServerSocketChannel>,
    ): PeerFixture {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(home.toString().toByteArray()).take(16).joinToString("") {
                "%02x".format(it)
            }
        val directory = Files.createDirectories(home.resolve(".kast/ide-hosted/$digest"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val socket = directory.resolve("host.sock")
        val descriptor = Descriptor(home.toString(), socket.toString())
        Files.writeString(directory.resolve("endpoint.json"), json.encodeToString(Descriptor.serializer(), descriptor))
        return PeerFixture(
            metadata = metadata,
            omitEvidence = omitEvidence,
            replaceSocket = replaceSocket,
            socket = socket,
            descriptor = descriptor,
            successors = successors,
        )
    }

    private inner class PeerFixture(
        val metadata: ArrayDeque<HostedCompatibilityDocument>,
        val omitEvidence: Boolean,
        val replaceSocket: Boolean,
        val socket: Path,
        val descriptor: Descriptor,
        val successors: MutableList<ServerSocketChannel>,
    ) {
        fun respond(channel: SocketChannel, operation: String): String {
            val input = DataInputStream(Channels.newInputStream(channel))
            val request = Json.parseToJsonElement(input.readNBytes(input.readInt()).decodeToString()).jsonObject
            val received = request.getValue("type").jsonPrimitive.content
            assertEquals(operation, received, "unexpected fixture request")
            val bytes = document(operation).toByteArray()
            if (replaceSocket) {
                Files.move(socket, socket.parent.resolve("retired.sock"))
                val successor = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                successors += successor
                successor.bind(UnixDomainSocketAddress.of(socket))
                successor.configureBlocking(false)
            }
            DataOutputStream(Channels.newOutputStream(channel)).apply {
                writeInt(bytes.size)
                write(bytes)
                flush()
            }
            return received
        }

        private fun document(operation: String): String {
            if (operation != "DESCRIBE") return json.encodeToString(Rejection.serializer(), Rejection())
            val description = metadata.removeFirst()
            return if (omitEvidence) {
                json.encodeToString(
                    LegacyHost.serializer(),
                    LegacyHost(descriptor.root, descriptor.host, descriptor.hostPid),
                )
            } else {
                json.encodeToString(
                    Host.serializer(),
                    Host(
                        root = descriptor.root,
                        host = descriptor.host,
                        hostPid = descriptor.hostPid,
                        compatibility = description,
                    ),
                )
            }
        }
    }

    @Serializable
    data class Descriptor(
        val root: String,
        val socket: String,
        val type: String = "KAST_IDE_ENDPOINT",
        val protocol: Int = 3,
        val hostPid: Long = ProcessHandle.current().pid(),
        val host: String = "00000000-0000-0000-0000-000000000002",
        val querySchema: String = "kast.query.run.v5",
        val operations: List<String> = operations(),
    )

    @Serializable
    private data class Host(
        val root: String,
        val host: String,
        val hostPid: Long,
        val compatibility: HostedCompatibilityDocument,
        val type: String = "KAST_IDE_HOST",
        val protocol: Int = 3,
        val querySchema: String = "kast.query.run.v5",
        val indexAuthority: String = "existing_ide_kotlin_stub_index",
        val operations: List<String> = operations(),
    )

    @Serializable
    private data class Rejection(val type: String = "HOST_REJECTED", val failure: String = "INVALID_REQUEST")

    @Serializable
    private data class LegacyHost(
        val root: String,
        val host: String,
        val hostPid: Long,
        val type: String = "KAST_IDE_HOST",
        val protocol: Int = 3,
        val querySchema: String = "kast.query.run.v5",
        val indexAuthority: String = "existing_ide_kotlin_stub_index",
        val operations: List<String> = operations(),
    )

    companion object {
        private fun operations() =
            listOf(
                "DESCRIBE",
                "CLASS_LOOKUP",
                "DIRECT_SUPERTYPE",
                "QUERY_RUN",
                "SOURCE_READ",
                "DIAGNOSTIC_CHECK",
                "CHANGE_PLAN",
                "CHANGE_APPLY",
                "CHANGE_RECOVER",
            )
    }
}
