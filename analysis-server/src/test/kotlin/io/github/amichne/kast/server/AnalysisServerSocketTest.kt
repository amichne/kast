package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.DescriptorRegistryPath
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.client.ProcessId
import io.github.amichne.kast.api.client.ProcessStartEpochMillis
import io.github.amichne.kast.api.client.SocketOwnerUid
import io.github.amichne.kast.api.client.RuntimeProcessIdentity
import io.github.amichne.kast.api.client.RuntimeSocketPath
import io.github.amichne.kast.api.client.RuntimeWorkspaceRoot
import io.github.amichne.kast.api.client.ServerInstanceOwnership
import io.github.amichne.kast.api.client.UnixDomainSocketTransport
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.RuntimeLifecycleAction
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
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
        val socketPath = tempDir.resolve("run").resolve("indexer.sock")
        val descriptorDirectory = tempDir.resolve("instances")
        val runtimeInstanceId = RuntimeInstanceId.parse("550e8400-e29b-41d4-a716-446655440000")
        val runningServer = AnalysisServer(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(
                transport = AnalysisTransport.UnixDomainSocket(socketPath),
                descriptorDirectory = descriptorDirectory,
                runtimeInstanceId = runtimeInstanceId,
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
            val descriptor = requireNotNull(server.descriptor)
            val ownership = descriptor.ownership as ServerInstanceOwnership.Owned
            assertEquals(UnixDomainSocketTransport.UDS, descriptor.transport)
            assertEquals(RuntimeSocketPath.of(socketPath), descriptor.socketPath)
            assertEquals(runtimeInstanceId, ownership.runtimeInstanceId)
            assertTrue(ownership.processIdentity.processStartEpochMillis.value > 0)
            assertTrue(ownership.ownerUid.value >= 0)
            assertTrue(ownership.socketFileIdentity.device >= 0)
            assertTrue(ownership.socketFileIdentity.inode > 0)
            assertTrue(socketPath.exists())
            val actualSocket = readBoundSocketEvidence(socketPath)
            assertEquals(actualSocket.socketOwnerUid, ownership.ownerUid)
            assertTrue(
                actualSocket.socketOwnerUid.isOwnedBy(readEffectiveProcessOwnerUid(descriptorDirectory)),
                "The actual socket owner differs from the effective process owner",
            )

            val daemonsFile = descriptorDirectory.resolve("daemons.json")
            assertTrue(daemonsFile.exists(), "daemons.json should exist while server is running")
        }

        assertFalse(socketPath.exists())
    }

    @Test
    fun `second server cannot replace a reachable endpoint`() {
        val socketPath = tempDir.resolve("run").resolve("owned.sock")
        val first = AnalysisServer(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(
                transport = AnalysisTransport.UnixDomainSocket(socketPath),
                descriptorDirectory = tempDir.resolve("first-instances"),
            ),
        ).start()

        try {
            val secondStart = runCatching {
                AnalysisServer(
                    backend = FakeAnalysisBackend.sample(tempDir),
                    config = AnalysisServerConfig(
                        transport = AnalysisTransport.UnixDomainSocket(socketPath),
                        descriptorDirectory = tempDir.resolve("second-instances"),
                    ),
                ).start()
            }
            secondStart.getOrNull()?.close()

            assertTrue(secondStart.isFailure, "A second server replaced a reachable endpoint")
            val response = callSocket(
                socketPath,
                JsonRpcRequest(id = JsonPrimitive(11), method = "runtime/status"),
            )
            assertTrue(response.contains("\"backendName\":\"fake\""))
        } finally {
            first.close()
        }
    }

    @Test
    fun `closing an old server does not unlink a replacement socket`() {
        val socketPath = tempDir.resolve("run").resolve("replacement.sock")
        val running = AnalysisServer(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(
                transport = AnalysisTransport.UnixDomainSocket(socketPath),
                descriptorDirectory = tempDir.resolve("instances"),
            ),
        ).start()
        Files.delete(socketPath)
        val replacement = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        replacement.bind(UnixDomainSocketAddress.of(socketPath))

        try {
            running.close()
            assertTrue(socketPath.exists(), "The old server removed a replacement endpoint")
        } finally {
            replacement.close()
            Files.deleteIfExists(socketPath)
        }
    }

    @Test
    fun `stale endpoint is removed only after complete ownership proof`() {
        val socketPath = tempDir.resolve("run").resolve("stale.sock")
        Files.createDirectories(socketPath.parent)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { staleChannel ->
            staleChannel.bind(UnixDomainSocketAddress.of(socketPath))
        }
        val descriptorDirectory = tempDir.resolve("instances")
        Files.createDirectories(descriptorDirectory)
        val stale = ServerInstanceDescriptor(
            workspaceRoot = RuntimeWorkspaceRoot.canonicalize(tempDir),
            backendVersion = RuntimeImplementationVersion("old"),
            socketPath = RuntimeSocketPath.of(socketPath),
            ownership = ServerInstanceOwnership.Owned(
                runtimeInstanceId = RuntimeInstanceId.create(),
                processIdentity = RuntimeProcessIdentity(
                    processId = ProcessId.current(),
                    processStartEpochMillis = ProcessStartEpochMillis.of(1),
                ),
                ownerUid = SocketOwnerUid.of(
                    (Files.getAttribute(socketPath, "unix:uid") as Number).toLong(),
                ),
                socketFileIdentity = readSocketFileIdentity(socketPath),
            ),
        )
        DescriptorStore(DescriptorRegistryPath.of(descriptorDirectory.resolve("daemons.json"))).write(stale)

        AnalysisServer(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(
                transport = AnalysisTransport.UnixDomainSocket(socketPath),
                descriptorDirectory = descriptorDirectory,
            ),
        ).start().use { replacement ->
            assertTrue(socketPath.exists())
            val replacementOwner = requireNotNull(replacement.descriptor).ownership as ServerInstanceOwnership.Owned
            val staleOwner = stale.ownership as ServerInstanceOwnership.Owned
            assertTrue(replacementOwner.runtimeInstanceId != staleOwner.runtimeInstanceId)
        }
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
    fun `one response cannot run another response lifecycle action`() {
        val firstOutput = BlockingFlushOutputStream()
        val actionRan = AtomicBoolean(false)
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(transport = AnalysisTransport.Stdio),
            lifecycleController = RuntimeLifecycleController {
                { actionRan.set(true) }
            },
        )
        val firstServer = StdioRpcServer(
            dispatcher = dispatcher,
            input = rpcInput(id = 1, method = "runtime/shutdown"),
            output = firstOutput,
        ).start()

        try {
            assertTrue(
                firstOutput.flushStarted.await(1, TimeUnit.SECONDS),
                "The lifecycle response never reached its flush boundary",
            )

            val secondServer = StdioRpcServer(
                dispatcher = dispatcher,
                input = rpcInput(id = 2, method = "runtime/status"),
                output = ByteArrayOutputStream(),
            ).start()
            secondServer.await()

            assertFalse(
                actionRan.get(),
                "A different response ran the lifecycle action before its owning response was flushed",
            )
        } finally {
            firstOutput.releaseFlush.countDown()
            firstServer.await()
        }

        assertTrue(actionRan.get(), "The owning response did not run its lifecycle action after flushing")
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

    private fun rpcInput(id: Int, method: String): ByteArrayInputStream = ByteArrayInputStream(
        json.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(id = JsonPrimitive(id), method = method),
        ).plus('\n').toByteArray(),
    )

    private class BlockingFlushOutputStream : ByteArrayOutputStream() {
        val flushStarted = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)

        override fun flush() {
            flushStarted.countDown()
            check(releaseFlush.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release the blocked response flush"
            }
            super.flush()
        }
    }
}
