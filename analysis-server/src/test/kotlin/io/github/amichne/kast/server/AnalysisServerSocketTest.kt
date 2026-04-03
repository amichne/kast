package io.github.amichne.kast.server

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

        val descriptor = runningServer.use { runningServer ->
            assertNotNull(runningServer.descriptor)
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
            assertEquals("uds", runningServer.descriptor?.transport)
            assertEquals(socketPath.toString(), runningServer.descriptor?.socketPath)
            assertTrue(socketPath.exists())
            DescriptorStore(descriptorDirectory).pathFor(checkNotNull(runningServer.descriptor))
        }

        assertFalse(socketPath.exists())
        assertFalse(descriptor.exists())
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
}
