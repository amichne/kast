package io.github.amichne.kast.server

import io.github.amichne.kast.api.AnalysisTransport
import io.github.amichne.kast.api.JsonRpcRequest
import io.github.amichne.kast.api.JsonRpcSuccessResponse
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.exists

class AnalysisServerSocketTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    @Test
    fun `socket transport writes descriptor, serves rpc, and cleans up`() {
        val socketPath = tempDir.resolve("run").resolve("standalone.sock")
        val descriptorDirectory = tempDir.resolve("instances")
        val runningServer = AnalysisServer(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(
                transport = AnalysisTransport.UnixDomainSocket(socketPath),
                descriptorDirectory = descriptorDirectory,
            ),
        ).start()

        runningServer.use { server ->
            assertNotNull(server.descriptor)
            val response = callSocket(
                socketPath = socketPath,
                request = JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "runtime/status",
                ),
            )
            val success = json.decodeFromString(JsonRpcSuccessResponse.serializer(), response)
            val status = json.decodeFromJsonElement(
                RuntimeStatusResponse.serializer(),
                success.result,
            )

            assertEquals("fake", status.backendName)
            assertEquals("uds", server.descriptor?.transport)
            assertEquals(socketPath.toString(), server.descriptor?.socketPath)
            assertTrue(socketPath.exists())

            val daemonsFile = descriptorDirectory.resolve("daemons.json")
            assertTrue(daemonsFile.exists(), "daemons.json should exist while server is running")
        }

        assertFalse(socketPath.exists())
    }

    @Test
    fun `stdio transport processes line-delimited rpc requests`() {
        val input = ByteArrayInputStream(
            buildString {
                append(
                    json.encodeToString(
                        JsonRpcRequest.serializer(),
                        JsonRpcRequest(id = JsonPrimitive(1), method = "runtime/status"),
                    ),
                )
                append('\n')
                append(
                    json.encodeToString(
                        JsonRpcRequest.serializer(),
                        JsonRpcRequest(id = JsonPrimitive(2), method = "capabilities"),
                    ),
                )
                append('\n')
            }.toByteArray(),
        )
        val output = ByteArrayOutputStream()
        val server = StdioRpcServer(
            dispatcher = AnalysisDispatcher(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(transport = AnalysisTransport.Stdio),
            ),
            input = input,
            output = output,
        ).start()

        server.await()

        val lines = output.toString(StandardCharsets.UTF_8).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("\"state\":\"READY\""))
        assertTrue(lines.last().contains("\"backendName\":\"fake\""))
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
}
