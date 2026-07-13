package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.contract.RuntimeLifecycleAction
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSelector
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSnapshot
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationState
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
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
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
        val socketPath = tempDir.resolve("run").resolve("headless.sock")
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
            dispatcher = RpcAnalysisDispatcher(
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
    fun `stdio transport flushes lifecycle response before running lifecycle action`() {
        val input = ByteArrayInputStream(
            json.encodeToString(
                JsonRpcRequest.serializer(),
                JsonRpcRequest(id = JsonPrimitive(1), method = "runtime/shutdown"),
            ).plus('\n').toByteArray(),
        )
        val output = ByteArrayOutputStream()
        val outputSizeWhenActionRan = mutableListOf<Int>()
        val server = StdioRpcServer(
            dispatcher = RpcAnalysisDispatcher(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(transport = AnalysisTransport.Stdio),
                lifecycleController = RuntimeLifecycleController { action ->
                    {
                        assertEquals(RuntimeLifecycleAction.SHUTDOWN, action)
                        outputSizeWhenActionRan += output.size()
                    }
                },
            ),
            input = input,
            output = output,
        ).start()

        server.await()

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"action\":\"SHUTDOWN\""))
        assertEquals(1, outputSizeWhenActionRan.size)
        assertTrue(outputSizeWhenActionRan.single() > 0, "Lifecycle action ran before response bytes were flushed")
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
    fun `mutation status remains retrievable by idempotency key after client disconnect`() {
        val socketPath = tempDir.resolve("run").resolve("m.sock")
        val target = tempDir.resolve("src/Reconnected.kt")
        val contentFile = tempDir.resolve("reconnected-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Reconnected\n")
        val idempotencyKey = KastMutationIdempotencyKey("issue-333-reconnect")
        val mutation = KastSemanticMutation.AddFile(
            idempotencyKey = idempotencyKey,
            request = KastAddFileRequest(
                workspaceRoot = tempDir.toString(),
                filePath = target.toString(),
                contentFile = contentFile.toString(),
            ),
        )

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
                    method = "mutation/submit",
                    params = json.encodeToJsonElement(KastSemanticMutation.serializer(), mutation),
                ),
            )
            Thread.sleep(25)

            val selector = KastMutationOperationSelector.ByIdempotencyKey(idempotencyKey)
            val terminal = awaitMutationTerminal(socketPath, selector)

            assertTrue(terminal.state is KastMutationOperationState.Completed)
            assertEquals("package sample\n\nclass Reconnected\n", Files.readString(target))
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

    private fun awaitMutationTerminal(
        socketPath: Path,
        selector: KastMutationOperationSelector,
    ): KastMutationOperationSnapshot {
        repeat(200) {
            val response = callSocket(
                socketPath = socketPath,
                request = JsonRpcRequest(
                    id = JsonPrimitive(2),
                    method = "mutation/status",
                    params = json.encodeToJsonElement(KastMutationOperationSelector.serializer(), selector),
                ),
            )
            val success = json.decodeFromString(JsonRpcSuccessResponse.serializer(), response)
            val snapshot = json.decodeFromJsonElement(KastMutationOperationSnapshot.serializer(), success.result)
            if (
                snapshot.state is KastMutationOperationState.Completed ||
                snapshot.state is KastMutationOperationState.Failed ||
                snapshot.state is KastMutationOperationState.Cancelled
            ) {
                return snapshot
            }
            Thread.sleep(5)
        }
        error("Mutation operation did not become terminal after reconnect")
    }

    private fun awaitClientHandlerCompletion() {
        repeat(50) {
            Thread.sleep(10)
        }
    }
}
