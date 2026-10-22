package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.direct.directToolDocument
import io.github.amichne.kast.cli.installedHostedBootstrap
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastMcpCancellationTest {
    @Test
    fun `exact cancellation interrupts runner socket and suppresses its response`(@TempDir root: Path) =
        assertInterruptibleQuery(root, CancellationTrigger.NOTIFICATION)

    @Test
    fun `input EOF interrupts runner socket and drains its worker`(@TempDir root: Path) =
        assertInterruptibleQuery(root, CancellationTrigger.END_OF_INPUT)

    private fun assertInterruptibleQuery(root: Path, trigger: CancellationTrigger) {
        Files.writeString(root.resolve("settings.gradle.kts"), "")
        ServerSocketChannel.open().use { listener ->
            listener.bind(InetSocketAddress("127.0.0.1", 0))
            SocketChannel.open(listener.localAddress).use { socket ->
                listener.accept().use { peer -> assertConnectedCancellation(root, trigger, socket, peer) }
            }
        }
    }

    private fun assertConnectedCancellation(
        root: Path,
        trigger: CancellationTrigger,
        socket: SocketChannel,
        peer: SocketChannel,
    ) {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val input = PipedInputStream()
        val writer = PipedOutputStream(input)
        val executor = Executors.newSingleThreadExecutor()
        val server = interruptibleServer(root, socket, started, cancelled)
        val running = executor.submit { server.run(BufferedInputStream(input), PrintStream(output)) }
        try {
            writer.send(McpRequest(id = JsonPrimitive(0), method = "initialize"))
            writer.send(
                McpRequest(
                    id = JsonPrimitive(1),
                    method = "tools/call",
                    params = mcpWire.encodeToJsonElement(CancellationTestCall()),
                )
            )
            assertTrue(started.await(5, TimeUnit.SECONDS), "The invocation must start")
            writer.cancelQuery(trigger)
            assertTrue(cancelled.await(5, TimeUnit.SECONDS), "The caller must interrupt the socket")
            if (trigger == CancellationTrigger.NOTIFICATION) {
                writer.send(McpRequest(id = JsonPrimitive(2), method = "ping"))
                writer.close()
            }
            running.get(5, TimeUnit.SECONDS)
            assertResponseIds(output, trigger)
            assertEquals(-1, peer.read(ByteBuffer.allocate(1)), "Cancellation closes the exact socket")
        } finally {
            socket.close()
            writer.close()
            input.close()
            executor.shutdownNow()
        }
    }

    private fun interruptibleServer(
        root: Path,
        socket: SocketChannel,
        started: CountDownLatch,
        cancelled: CountDownLatch,
    ): KastMcpServer =
        KastMcpServer(
            catalog =
                listOf(installedHostedBootstrap().tools.single { it.name == "query_symbols" }.directToolDocument()),
            invoke = { _, _ ->
                started.countDown()
                try {
                    socket.read(ByteBuffer.allocate(1))
                    error("The peer sends no semantic response")
                } catch (_: ClosedByInterruptException) {
                    cancelled.countDown()
                    throw CancellationException("Caller cancelled the request")
                }
            },
            root = { FilesystemCanonicalRootDiscovery.discover(root) },
            diagnostic = PrintStream(ByteArrayOutputStream()),
        )

    private fun assertResponseIds(output: ByteArrayOutputStream, trigger: CancellationTrigger) {
        val ids =
            output
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .map { mcpWire.parseToJsonElement(it).jsonObject.getValue("id") }
                .toList()
        assertEquals(
            when (trigger) {
                CancellationTrigger.NOTIFICATION -> listOf(JsonPrimitive(0), JsonPrimitive(2))
                CancellationTrigger.END_OF_INPUT -> listOf(JsonPrimitive(0))
            },
            ids,
        )
    }
}

private fun PipedOutputStream.cancelQuery(trigger: CancellationTrigger) =
    when (trigger) {
        CancellationTrigger.NOTIFICATION -> {
            send(
                McpRequest(
                    method = "notifications/cancelled",
                    params = mcpWire.encodeToJsonElement(CancellationTestParams(JsonPrimitive(99))),
                )
            )
            send(
                McpRequest(
                    method = "notifications/cancelled",
                    params = mcpWire.encodeToJsonElement(CancellationTestParams(JsonPrimitive(1))),
                )
            )
        }
        CancellationTrigger.END_OF_INPUT -> close()
    }

private fun PipedOutputStream.send(request: McpRequest) {
    write((mcpWire.encodeToString(McpRequest.serializer(), request) + "\n").toByteArray())
    flush()
}

@Serializable
private data class CancellationTestCall(
    val name: String = "query_symbols",
    val arguments: CancellationTestArguments = CancellationTestArguments(),
)

@Serializable private data class CancellationTestArguments(val request: CancellationTestRun = CancellationTestRun())

@Serializable
private data class CancellationTestRun(
    val type: String = "RUN",
    val source: CancellationTestSource = CancellationTestSource(),
    val terminalStep: CancellationTestTerminal = CancellationTestTerminal(),
    val executionMode: String = "RUNNER_CONTROLLED",
)

@Serializable private data class CancellationTestTerminal(val type: String = "EMIT")

@Serializable
private data class CancellationTestSource(
    val type: String = "SEARCH_DECLARATIONS",
    val declarationName: String = "OrderRepository",
    val declarationKinds: List<String> = listOf("CLASS"),
    val scope: CancellationTestScope = CancellationTestScope(),
)

@Serializable
private data class CancellationTestScope(
    val type: String = "DIRECTORY",
    val relativeDirectoryPath: String = ".",
    val sourceSetNames: List<String> = listOf("main"),
)

@Serializable private data class CancellationTestParams(val requestId: JsonPrimitive)

private enum class CancellationTrigger {
    NOTIFICATION,
    END_OF_INPUT,
}
