package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.RuntimeLifecycleAction
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
    fun `mutation retry joins its terminal result without reapplying`() {
        val socketPath = tempDir.resolve("run").resolve("mutation-retry.sock")
        val target = tempDir.resolve("src/Retried.kt")
        val contentFile = tempDir.resolve("retried-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass Retried\n")
        val applyStarted = CompletableDeferred<Unit>()
        val mutation = KastSemanticMutation.AddFile(
            idempotencyKey = KastMutationIdempotencyKey("issue-333-reconnect"),
            request = KastAddFileRequest(
                workspaceRoot = tempDir.toString(),
                filePath = target.toString(),
                contentFile = contentFile.toString(),
            ),
        )

        AnalysisServer(
            backend = AdmittedApplyBackend(FakeAnalysisBackend.sample(tempDir), applyStarted),
            config = AnalysisServerConfig(
                transport = AnalysisTransport.UnixDomainSocket(socketPath),
                descriptorDirectory = tempDir.resolve("mutation-retry-instances"),
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
            runBlocking { withTimeout(1_000) { applyStarted.await() } }

            val response = callSocket(
                socketPath = socketPath,
                request = JsonRpcRequest(
                    id = JsonPrimitive(2),
                    method = "mutation/submit",
                    params = json.encodeToJsonElement(KastSemanticMutation.serializer(), mutation),
                ),
            )
            val success = json.decodeFromString(JsonRpcSuccessResponse.serializer(), response)
            val terminal = json.decodeFromJsonElement(KastMutationExecutionResult.serializer(), success.result)

            assertTrue(terminal is KastMutationExecutionResult.Succeeded)
            assertTrue(terminal.deduplicated)
            assertEquals("package sample\n\nclass Retried\n", Files.readString(target))
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

    private fun rpcInput(id: Int, method: String): ByteArrayInputStream = ByteArrayInputStream(
        json.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(id = JsonPrimitive(id), method = method),
        ).plus('\n').toByteArray(),
    )

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

    @Test
    fun `running server closes its backend exactly once`() {
        val socketPath = tempDir.resolve("run").resolve("owned-backend.sock")
        val backend = CountingCloseBackend(FakeAnalysisBackend.sample(tempDir))
        val runningServer = AnalysisServer(
            backend = backend,
            config = AnalysisServerConfig(
                transport = AnalysisTransport.UnixDomainSocket(socketPath),
                descriptorDirectory = tempDir.resolve("owned-backend-instances"),
            ),
        ).start()

        runningServer.close()
        runningServer.close()

        assertEquals(1, backend.closeCount)
    }

    @Test
    fun `running server drains admitted requests before closing backend`() {
        val socketPath = tempDir.resolve("run").resolve("drain-before-close.sock")
        val backend = SuspendedStatusBackend(FakeAnalysisBackend.sample(tempDir))
        val runningServer = AnalysisServer(
            backend = backend,
            config = AnalysisServerConfig(
                transport = AnalysisTransport.UnixDomainSocket(socketPath),
                descriptorDirectory = tempDir.resolve("drain-before-close-instances"),
            ),
        ).start()
        val clientThread = thread(name = "kast-test-active-client") {
            runCatching {
                callSocket(
                    socketPath = socketPath,
                    request = JsonRpcRequest(id = JsonPrimitive(1), method = "runtime/status"),
                )
            }
        }
        assertTrue(backend.started.await(1, TimeUnit.SECONDS), "The backend request was not admitted")
        val closeThread = thread(name = "kast-test-server-close") {
            runningServer.close()
        }

        try {
            closeThread.join(1_250)
            assertTrue(closeThread.isAlive, "Server close returned while an admitted request was still active")
            assertEquals(0, backend.closeCount, "Backend closed while an admitted request was still active")
        } finally {
            backend.release()
            closeThread.join(3_000)
            clientThread.join(3_000)
            if (closeThread.isAlive) {
                closeThread.interrupt()
            }
        }

        assertFalse(closeThread.isAlive, "Server close did not finish after the admitted request completed")
        assertFalse(backend.closedWhileActive, "Backend closed before the admitted request completed")
        assertEquals(1, backend.closeCount)
    }

    @Test
    fun `running server completes later close phases after earlier failures`() {
        val descriptorFile = tempDir.resolve("failure-instances").resolve("daemons.json")
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = tempDir.toString(),
            backendName = "fake",
            backendVersion = "test",
            socketPath = tempDir.resolve("failure.sock").toString(),
        )
        val descriptorStore = DescriptorStore(descriptorFile.toString()).also { it.write(descriptor) }
        val closeEvents = mutableListOf<String>()
        val transportFailure = IllegalStateException("transport close failed")
        val backend = RecordingCloseBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            closeEvents = closeEvents,
            beforeClose = {
                assertTrue(Files.readString(descriptorFile).contains(descriptor.socketPath))
            },
        )
        val runningServer = RunningAnalysisServer(
            server = RecordingLocalRpcServer(closeEvents, transportFailure),
            dispatcher = RecordingCloseable(closeEvents, "dispatcher", transportFailure),
            backend = backend,
            descriptor = descriptor,
            descriptorStore = descriptorStore,
        )

        val failure = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            runningServer.close()
        }
        runningServer.close()

        assertEquals(transportFailure, failure)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(listOf("transport", "dispatcher", "backend"), closeEvents)
        assertEquals(1, backend.closeCount)
        assertFalse(
            Files.exists(descriptorFile) && Files.readString(descriptorFile).contains(descriptor.socketPath),
            "descriptor cleanup was skipped after an earlier close failure",
        )
    }
    @Test
    fun `failed start preserves caller backend ownership and releases provisional server`() {
        val socketPath = tempDir.resolve("run").resolve("failed-start.sock")
        val invalidDescriptorDirectory = tempDir.resolve("descriptor-file")
        Files.writeString(invalidDescriptorDirectory, "not a directory")
        val backend = CountingCloseBackend(FakeAnalysisBackend.sample(tempDir))

        try {
            org.junit.jupiter.api.assertThrows<Throwable> {
                AnalysisServer(
                    backend = backend,
                    config = AnalysisServerConfig(
                        transport = AnalysisTransport.UnixDomainSocket(socketPath),
                        descriptorDirectory = invalidDescriptorDirectory,
                    ),
                ).start()
            }

            assertEquals(0, backend.closeCount, "failed start transferred backend ownership")
            assertFalse(socketPath.exists(), "failed start leaked its provisional transport")
        } finally {
            backend.close()
            Files.deleteIfExists(socketPath)
        }
        assertEquals(1, backend.closeCount)
    }

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

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
    }
}
