package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.mutation.KastMutationExecutionResult
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcErrorObject
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.protocol.JSON_RPC_SERVER_ERROR_BASE
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.exists

class AnalysisServerTransportLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `unix socket shutdown disconnects an accepted idle client`() {
        val socketPath = tempDir.resolve("run").resolve("idle-client.sock")
        val server = UnixDomainSocketRpcServer(
            socketPath = socketPath,
            dispatcher = RpcAnalysisDispatcher(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(),
            ),
        ).start()
        val client = SocketChannel.open(StandardProtocolFamily.UNIX)

        try {
            client.connect(UnixDomainSocketAddress.of(socketPath))
            awaitCondition("The Unix socket server did not accept the client") {
                retainedHandlerCount(server) == 1
            }

            server.close()

            assertPeerClosed(client)
            assertFalse(transportAcceptThreadIsAlive(server), "Unix socket shutdown retained its accept thread")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `unix socket forgets completed client handlers`() {
        val socketPath = tempDir.resolve("run").resolve("completed-client.sock")
        val server = UnixDomainSocketRpcServer(
            socketPath = socketPath,
            dispatcher = RpcAnalysisDispatcher(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(),
            ),
        ).start()

        try {
            callSocket(
                socketPath = socketPath,
                request = JsonRpcRequest(id = JsonPrimitive(1), method = "runtime/status"),
            )

            awaitCondition("The Unix socket server retained a completed client handler") {
                retainedHandlerCount(server) == 0
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `tcp shutdown disconnects an accepted idle client`() {
        val server = TcpRpcServer(
            host = LOOPBACK_ADDRESS,
            port = 0,
            dispatcher = RpcAnalysisDispatcher(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(),
            ),
        ).start()
        val client = SocketChannel.open(StandardProtocolFamily.INET)

        try {
            client.connect(InetSocketAddress(LOOPBACK_ADDRESS, server.boundPort()))
            awaitCondition("The TCP server did not accept the client") {
                retainedHandlerCount(server) == 1
            }

            server.close()

            assertPeerClosed(client)
            assertFalse(transportAcceptThreadIsAlive(server), "TCP shutdown retained its accept thread")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `tcp forgets completed client handlers`() {
        val server = TcpRpcServer(
            host = LOOPBACK_ADDRESS,
            port = 0,
            dispatcher = RpcAnalysisDispatcher(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(),
            ),
        ).start()

        try {
            callTcpSocket(
                port = server.boundPort(),
                request = JsonRpcRequest(id = JsonPrimitive(1), method = "runtime/status"),
            )

            awaitCondition("The TCP server retained a completed client handler") {
                retainedHandlerCount(server) == 0
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `rpc handler joins share one shutdown deadline`() {
        val release = CountDownLatch(1)
        val first = thread(name = "kast-test-blocked-handler-1") { release.await() }
        val second = thread(name = "kast-test-blocked-handler-2") { release.await() }
        val startedNanos = System.nanoTime()

        try {
            joinRpcThreadsUntil(
                threads = listOf(first, second),
                currentThread = Thread.currentThread(),
                deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(100),
            )
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

            assertTrue(elapsedMillis < 300, "Handler joins used one timeout per thread: ${elapsedMillis}ms")
        } finally {
            release.countDown()
            first.join(1_000)
            second.join(1_000)
        }
    }

    @Test
    fun `unix socket start failure closes its provisional channel`() {
        val invalidParent = tempDir.resolve("not-a-directory")
        Files.writeString(invalidParent, "file")
        val server = UnixDomainSocketRpcServer(
            socketPath = invalidParent.resolve("server.sock"),
            dispatcher = RpcAnalysisDispatcher(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(),
            ),
        )

        try {
            org.junit.jupiter.api.assertThrows<Throwable> {
                server.start()
            }

            assertFalse(transportChannelIsOpen(server), "Failed Unix socket startup leaked its server channel")
        } finally {
            server.close()
        }
    }

    @Test
    fun `tcp start failure closes its provisional channel`() {
        ServerSocketChannel.open(StandardProtocolFamily.INET).use { occupied ->
            occupied.bind(InetSocketAddress(LOOPBACK_ADDRESS, 0))
            val occupiedPort = (occupied.localAddress as InetSocketAddress).port
            val server = TcpRpcServer(
                host = LOOPBACK_ADDRESS,
                port = occupiedPort,
                dispatcher = RpcAnalysisDispatcher(
                    backend = FakeAnalysisBackend.sample(tempDir),
                    config = AnalysisServerConfig(),
                ),
            )

            try {
                org.junit.jupiter.api.assertThrows<Throwable> {
                    server.start()
                }

                assertFalse(transportChannelIsOpen(server), "Failed TCP startup leaked its server channel")
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun `socket transport ignores client disconnects after request write`() {
        val socketPath = tempDir.resolve("run").resolve("disconnect.sock")
        val uncaughtClientErrors = CopyOnWriteArrayList<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name == "kast-uds-rpc-client") {
                uncaughtClientErrors += error
            } else {
                previousHandler?.uncaughtException(thread, error)
            }
        }

        try {
            AnalysisServer(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(
                    transport = AnalysisTransport.UnixDomainSocket(socketPath),
                    descriptorDirectory = tempDir.resolve("instances"),
                ),
            ).start().use {
                sendWithoutReadingResponse(
                    socketPath = socketPath,
                    request = JsonRpcRequest(
                        id = JsonPrimitive(1),
                        method = "runtime/status",
                    ),
                )

                val response = callSocket(
                    socketPath = socketPath,
                    request = JsonRpcRequest(
                        id = JsonPrimitive(2),
                        method = "runtime/status",
                    ),
                )

                assertTrue(response.contains("\"state\":\"READY\""))
                awaitClientHandlerCompletion()
                assertTrue(uncaughtClientErrors.isEmpty(), "Unexpected uncaught client errors: $uncaughtClientErrors")
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun `expected client disconnects include macOS disconnected socket errors`() {
        assertTrue(isExpectedClientDisconnect(IOException("Socket is not connected")))
    }

    private fun callSocket(
        socketPath: Path,
        request: JsonRpcRequest,
    ): String {
        return SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            val writer = Channels.newWriter(channel, StandardCharsets.UTF_8.name()).buffered()
            val reader = Channels.newReader(channel, StandardCharsets.UTF_8.name()).buffered()
            writer.write(json.encodeToString(JsonRpcRequest.serializer(), request))
            writer.newLine()
            writer.flush()
            checkNotNull(reader.readLine())
        }
    }

    private fun callTcpSocket(
        port: Int,
        request: JsonRpcRequest,
    ): String = SocketChannel.open(StandardProtocolFamily.INET).use { channel ->
        channel.connect(InetSocketAddress(LOOPBACK_ADDRESS, port))
        val writer = Channels.newWriter(channel, StandardCharsets.UTF_8.name()).buffered()
        val reader = Channels.newReader(channel, StandardCharsets.UTF_8.name()).buffered()
        writer.write(json.encodeToString(JsonRpcRequest.serializer(), request))
        writer.newLine()
        writer.flush()
        checkNotNull(reader.readLine())
    }

    private fun assertPeerClosed(channel: SocketChannel) {
        channel.configureBlocking(false)
        val buffer = ByteBuffer.allocate(1)
        awaitCondition("Server shutdown left the accepted client connected") {
            try {
                channel.read(buffer) == -1
            } catch (_: IOException) {
                true
            }
        }
    }

    private fun retainedHandlerCount(server: LocalRpcServer): Int {
        val handlersField = server.javaClass.getDeclaredField("handlers").apply {
            trySetAccessible()
        }
        val handlers = handlersField.get(server) as Collection<*>
        return synchronized(handlers) {
            handlers.size
        }
    }

    private fun transportChannelIsOpen(server: LocalRpcServer): Boolean {
        val channelField = server.javaClass.getDeclaredField("serverChannel").apply {
            trySetAccessible()
        }
        return (channelField.get(server) as ServerSocketChannel).isOpen
    }

    private fun transportAcceptThreadIsAlive(server: LocalRpcServer): Boolean {
        val acceptThreadField = server.javaClass.getDeclaredField("acceptThread").apply {
            trySetAccessible()
        }
        return (acceptThreadField.get(server) as Thread).isAlive
    }

    private fun awaitCondition(
        failureMessage: String,
        condition: () -> Boolean,
    ) {
        repeat(100) {
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }
        assertTrue(condition(), failureMessage)
    }

    private fun sendWithoutReadingResponse(
        socketPath: Path,
        request: JsonRpcRequest,
    ) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            val writer = Channels.newWriter(channel, StandardCharsets.UTF_8.name()).buffered()
            writer.write(json.encodeToString(JsonRpcRequest.serializer(), request))
            writer.newLine()
            writer.flush()
        }
    }

    private fun awaitClientHandlerCompletion() {
        repeat(50) {
            Thread.sleep(10)
        }
    }

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
    }
}
