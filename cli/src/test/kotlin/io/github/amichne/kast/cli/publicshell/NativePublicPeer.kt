package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
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
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

internal enum class NativePeerReply {
    HOLD_QUERY,
    HOST_REJECTION,
    WRONG_HOST,
    WRONG_ROOT,
    INCOMPATIBLE,
    REPLACED_SOCKET,
    MALFORMED,
    DUPLICATE_KEY,
    TRAILING_DOCUMENT,
}

/**
 * Mirrors HostedSocketExchangeFixture framing; that owner is private to app-server's test source set. These real peers
 * qualify native transport/admission, never compiler semantics or IDEA settlement.
 */
internal class NativePublicPeer(private val fixture: NativePublicFixture, private val reply: NativePeerReply) :
    AutoCloseable {
    private val json = Json { encodeDefaults = true }
    private val target =
        IdeProjectTarget(
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
            fixture.workspace.toString(),
        )
    private val properties =
        Properties().apply {
            checkNotNull(CanonicalHostedContract::class.java.getResourceAsStream("/kast-hosted-platform.properties"))
                .use(::load)
        }
    private val compatibility =
        HostedCompatibilityDocument(
            properties.getProperty("ideBuild"),
            properties.getProperty("kotlinPluginBuild"),
            "0.0.0",
            CanonicalHostedContract.document,
        )
    private val lifecycle = listener("ide-lifecycle", fixture.idea)
    private val hosted = listener("ide-hosted", fixture.workspace)
    private val replacements = mutableListOf<ServerSocketChannel>()
    private val executor = Executors.newSingleThreadExecutor()
    private val queryStarted = CompletableFuture<Unit>()
    private val worker = executor.submit {
        prepareWorkspace()
        hosted.accept().use { channel ->
            assertRequest(channel, "DESCRIBE")
            if (reply == NativePeerReply.REPLACED_SOCKET) {
                val socket = (hosted.localAddress as UnixDomainSocketAddress).path
                // Keep the original inode alive: reuse cannot make this a same-incarnation control.
                Files.move(socket, socket.resolveSibling("prior.sock"))
                replacements.add(listener("ide-hosted", fixture.workspace))
            }
            writeFrame(channel, statusReply())
        }
        if (reply == NativePeerReply.HOLD_QUERY || reply == NativePeerReply.HOST_REJECTION) {
            hosted.accept().use { channel ->
                assertRequest(channel, "QUERY_RUN")
                queryStarted.complete(Unit)
                when (reply) {
                    NativePeerReply.HOLD_QUERY -> assertEquals(-1, Channels.newInputStream(channel).read())
                    NativePeerReply.HOST_REJECTION -> writeFrame(channel, json.encodeToString(PeerRejection()))
                    else -> error("Unexpected peer response")
                }
            }
        }
    }

    init {
        val socket = (hosted.localAddress as UnixDomainSocketAddress).path
        Files.writeString(
            socket.parent.resolve("endpoint.json"),
            json.encodeToString(PeerDescriptor(target.root, socket.toString(), target.project)),
        )
    }

    fun awaitQuery() {
        queryStarted.get(20, TimeUnit.SECONDS)
    }

    fun assertConsumed() {
        worker.get(20, TimeUnit.SECONDS)
        for (listener in listOf(lifecycle, hosted) + replacements) {
            listener.configureBlocking(false)
            listener.accept().use { assertNull(it, "Unexpected extra exchange or automatic replay") }
        }
    }

    private fun prepareWorkspace() {
        val inspected =
            IdeLifecycleResult.Inspected(
                target.host,
                fixture.idea.toString(),
                compatibility.ideBuild,
                listOf(IdeProjectDescription(target, IdeProjectOwnership.BORROWED, 1)),
                compatibility,
            )
        lifecycle.accept().use { channel ->
            assertEquals(IdeLifecycleCommand.Inspect, lifecycleRequest(channel))
            writeFrame(channel, json.encodeToString<IdeLifecycleResult>(inspected))
        }
        lifecycle.accept().use { channel ->
            val request = lifecycleRequest(channel)
            assertTrue(request is IdeLifecycleCommand.Open, request.toString())
            val open = request as IdeLifecycleCommand.Open
            assertEquals(target.root, open.root)
            assertEquals(target.host, open.host)
            writeFrame(channel, json.encodeToString<IdeLifecycleResult>(IdeLifecycleResult.Opened(target)))
        }
        lifecycle.accept().use { channel ->
            assertEquals(IdeLifecycleCommand.Inspect, lifecycleRequest(channel))
            writeFrame(channel, json.encodeToString<IdeLifecycleResult>(inspected))
        }
    }

    private fun lifecycleRequest(channel: SocketChannel): IdeLifecycleCommand =
        json.decodeFromString<IdeLifecycleCommand>(readFrame(channel))

    private fun assertRequest(channel: SocketChannel, expected: String) {
        val raw = readFrame(channel)
        Files.writeString(fixture.evidence.resolve("peer-$expected.request.json"), raw)
        val document = Json.parseToJsonElement(raw).jsonObject
        assertEquals(expected, document.getValue("type").jsonPrimitive.content)
        assertEquals(target.root, document.getValue("root").jsonPrimitive.content)
    }

    private fun statusReply(): String {
        val status = PeerHost(target.root, target.project, compatibility)
        return when (reply) {
            NativePeerReply.HOLD_QUERY,
            NativePeerReply.REPLACED_SOCKET,
            NativePeerReply.HOST_REJECTION -> json.encodeToString(status)
            NativePeerReply.WRONG_HOST -> json.encodeToString(status.copy(host = target.host))
            NativePeerReply.WRONG_ROOT -> json.encodeToString(status.copy(root = fixture.home.toString()))
            NativePeerReply.INCOMPATIBLE ->
                json.encodeToString(status.copy(compatibility = compatibility.copy(ideBuild = "261.1")))
            // Deliberate invalid boundary bytes: these must be rejected before semantic response admission.
            NativePeerReply.MALFORMED -> "{"
            NativePeerReply.DUPLICATE_KEY ->
                json.encodeToString(status).dropLast(1) + ",\"host\":\"${target.project}\"}"
            NativePeerReply.TRAILING_DOCUMENT -> json.encodeToString(status) + " {}"
        }
    }

    private fun listener(family: String, selected: Path): ServerSocketChannel {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(selected.toString().toByteArray()).take(16).joinToString("") {
                "%02x".format(it)
            }
        val directory = Files.createDirectories(fixture.home.resolve(".kast/$family/$digest"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val socket = directory.resolve("host.sock")
        check(socket.toString().toByteArray().size <= 100) { "Fixture socket exceeds production path bound" }
        return ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socket))
        }
    }

    override fun close() {
        worker.cancel(true)
        lifecycle.close()
        hosted.close()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Peer worker did not settle" }
        replacements.forEach(ServerSocketChannel::close)
    }
}

private fun readFrame(channel: SocketChannel): String {
    val input = DataInputStream(Channels.newInputStream(channel))
    val size = input.readInt()
    check(size in 1..1_048_576) { "Unexpected request frame size $size" }
    val bytes = input.readNBytes(size)
    assertEquals(size, bytes.size, "Truncated fixture request")
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

private fun writeFrame(channel: SocketChannel, value: String) {
    val bytes = value.toByteArray()
    DataOutputStream(Channels.newOutputStream(channel)).apply {
        writeInt(bytes.size)
        write(bytes)
        flush()
    }
}

@Serializable
private data class PeerDescriptor(
    val root: String,
    val socket: String,
    val host: String,
    val type: String = "KAST_IDE_ENDPOINT",
    val protocol: Int = 3,
    val hostPid: Long = ProcessHandle.current().pid(),
    val querySchema: String = "kast.query.run.v9",
    val operations: List<String> = peerOperations(),
)

@Serializable
private data class PeerHost(
    val root: String,
    val host: String,
    val compatibility: HostedCompatibilityDocument,
    val type: String = "KAST_IDE_HOST",
    val protocol: Int = 3,
    val hostPid: Long = ProcessHandle.current().pid(),
    val querySchema: String = "kast.query.run.v9",
    val indexAuthority: String = "existing_ide_kotlin_stub_index",
    val operations: List<String> = peerOperations(),
)

@Serializable
private data class PeerRejection(val type: String = "HOST_REJECTED", val failure: String = "INVALID_REQUEST")

private fun peerOperations(): List<String> =
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
