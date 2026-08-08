package io.github.amichne.kast.server

import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists

class UnixSocketAnalysisServerTransportLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `completed unix socket handlers do not convoy on the handler registry monitor`() {
        val socketPath = tempDir.resolve("run").resolve("cleanup-convoy.sock")
        val server = UnixDomainSocketRpcServer(
            socketPath = socketPath,
            dispatcher = RpcAnalysisDispatcher(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(),
            ),
        ).start()
        val responses = CopyOnWriteArrayList<String>()
        val clientFailures = CopyOnWriteArrayList<Throwable>()
        val responsesComplete = CountDownLatch(CLIENT_COUNT)
        val releaseClients = CountDownLatch(1)
        val clients = List(CLIENT_COUNT) { requestId ->
            thread(name = "kast-test-uds-client-$requestId") {
                runCatching {
                    responses += callSocketUntilReleased(
                        socketPath = socketPath,
                        requestId = requestId,
                        responsesComplete = responsesComplete,
                        releaseClients = releaseClients,
                    )
                }.onFailure(clientFailures::add)
            }
        }

        try {
            assertTrue(
                responsesComplete.await(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Unix socket clients did not all receive complete responses",
            )
            val handlerRegistry = handlerRegistry(server)
            val acceptedHandlers = synchronized(handlerRegistry) {
                handlerRegistry.map { entry ->
                    checkNotNull(entry as? Thread) { "Handler registry contained a non-thread owner" }
                }
            }
            assertEquals(CLIENT_COUNT, acceptedHandlers.size, "Handler cardinality exceeded accepted client ownership")

            synchronized(handlerRegistry) {
                releaseClients.countDown()
                joinRpcThreadsUntil(
                    threads = clients,
                    currentThread = Thread.currentThread(),
                    deadlineNanos = deadlineAfter(CLIENT_TIMEOUT_SECONDS),
                )
                assertTrue(clients.none(Thread::isAlive), "Unix socket clients did not close cleanly")
                joinRpcThreadsUntil(
                    threads = acceptedHandlers,
                    currentThread = Thread.currentThread(),
                    deadlineNanos = deadlineAfter(HANDLER_TIMEOUT_SECONDS),
                )
                assertTrue(
                    acceptedHandlers.none(Thread::isAlive),
                    "Unix socket handler cleanup convoyed on the shared handler registry monitor",
                )
            }

            assertEquals(CLIENT_COUNT, responses.size, "Unix socket transport lost completed responses")
            assertTrue(clientFailures.isEmpty(), "Unix socket clients failed: $clientFailures")
            assertTrue(handlerRegistry(server).isEmpty(), "Unix socket server retained completed handlers")

            server.close()

            assertFalse(acceptThread(server).isAlive, "Unix socket server retained its accept thread")
            assertFalse(socketPath.exists(), "Unix socket server retained its socket path")
        } finally {
            releaseClients.countDown()
            clients.forEach { client -> client.join(TimeUnit.SECONDS.toMillis(CLIENT_TIMEOUT_SECONDS)) }
            server.close()
        }
    }

    private fun callSocketUntilReleased(
        socketPath: Path,
        requestId: Int,
        responsesComplete: CountDownLatch,
        releaseClients: CountDownLatch,
    ): String = SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
        channel.connect(UnixDomainSocketAddress.of(socketPath))
        val writer = Channels.newWriter(channel, StandardCharsets.UTF_8.name()).buffered()
        val reader = Channels.newReader(channel, StandardCharsets.UTF_8.name()).buffered()
        writer.write(
            json.encodeToString(
                JsonRpcRequest.serializer(),
                JsonRpcRequest(id = JsonPrimitive(requestId), method = "runtime/status"),
            ),
        )
        writer.newLine()
        writer.flush()
        val response = checkNotNull(reader.readLine())
        responsesComplete.countDown()
        check(releaseClients.await(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting to release Unix socket client"
        }
        response
    }

    private fun handlerRegistry(server: UnixDomainSocketRpcServer): Collection<*> {
        val field = server.javaClass.getDeclaredField("handlers").apply { trySetAccessible() }
        return checkNotNull(field.get(server) as? Collection<*>)
    }

    private fun acceptThread(server: UnixDomainSocketRpcServer): Thread {
        val field = server.javaClass.getDeclaredField("acceptThread").apply { trySetAccessible() }
        return checkNotNull(field.get(server) as? Thread)
    }

    private fun deadlineAfter(seconds: Long): Long =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)

    private companion object {
        const val CLIENT_COUNT = 16
        const val CLIENT_TIMEOUT_SECONDS = 10L
        const val HANDLER_TIMEOUT_SECONDS = 5L
    }
}
