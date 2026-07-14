package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.RuntimeLifecycleAction
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSelector
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSnapshot
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationState
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.protocol.JSON_RPC_SERVER_ERROR_BASE
import io.github.amichne.kast.api.validation.ParsedApplyEditsQuery
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
            val selector = KastMutationOperationSelector.ByIdempotencyKey(idempotencyKey)
            val preAdmission = pollMutationStatus(socketPath, selector)
            assertEquals(MutationStatusPoll.NotAdmitted, preAdmission)
            sendWithoutReadingResponse(
                socketPath = socketPath,
                request = JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "mutation/submit",
                    params = json.encodeToJsonElement(KastSemanticMutation.serializer(), mutation),
                ),
            )
            val terminal = awaitMutationTerminal(socketPath, selector, preAdmission)

            assertTrue(terminal.state is KastMutationOperationState.Completed)
            assertEquals("package sample\n\nclass Reconnected\n", Files.readString(target))
        }
    }

    @Test
    fun `server close cancels and joins mutation before removing descriptor authority`() {
        val socketPath = tempDir.resolve("run").resolve("c.sock")
        val descriptorDirectory = tempDir.resolve("close-instances")
        val descriptorFile = descriptorDirectory.resolve("daemons.json")
        val target = tempDir.resolve("src/AfterClose.kt")
        val contentFile = tempDir.resolve("after-close-content.kt")
        Files.writeString(contentFile, "package sample\n\nclass AfterClose\n")
        val applyStarted = CompletableDeferred<Unit>()
        val applyStopped = CompletableDeferred<Unit>()
        val descriptorRetainedDuringStop = AtomicBoolean(false)
        val backend = ClosingApplyBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            applyStarted = applyStarted,
            applyStopped = applyStopped,
            descriptorFile = descriptorFile,
            descriptorIdentity = socketPath.toString(),
            descriptorRetainedDuringStop = descriptorRetainedDuringStop,
        )
        val server = AnalysisServer(
            backend = backend,
            config = AnalysisServerConfig(
                transport = AnalysisTransport.UnixDomainSocket(socketPath),
                descriptorDirectory = descriptorDirectory,
            ),
        ).start()
        val mutation = KastSemanticMutation.AddFile(
            idempotencyKey = KastMutationIdempotencyKey("issue-333-server-close"),
            request = KastAddFileRequest(
                workspaceRoot = tempDir.toString(),
                filePath = target.toString(),
                contentFile = contentFile.toString(),
            ),
        )
        callSocket(
            socketPath = socketPath,
            request = JsonRpcRequest(
                id = JsonPrimitive(1),
                method = "mutation/submit",
                params = json.encodeToJsonElement(KastSemanticMutation.serializer(), mutation),
            ),
        )
        runBlocking { withTimeout(1_000) { applyStarted.await() } }

        server.close()
        runBlocking { withTimeout(1_000) { applyStopped.await() } }
        Thread.sleep(300)

        assertFalse(Files.exists(target), "mutation wrote after server authority closed")
        assertTrue(
            descriptorRetainedDuringStop.get(),
            "descriptor authority was removed before the mutation worker stopped",
        )
        assertFalse(
            Files.exists(descriptorFile) && Files.readString(descriptorFile).contains(socketPath.toString()),
        )
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
        initialPoll: MutationStatusPoll,
    ): KastMutationOperationSnapshot {
        var poll = initialPoll
        repeat(200) {
            when (val current = poll) {
                MutationStatusPoll.NotAdmitted -> Unit
                is MutationStatusPoll.Available -> {
                    val snapshot = current.snapshot
                    if (
                        snapshot.state is KastMutationOperationState.Completed ||
                        snapshot.state is KastMutationOperationState.Failed ||
                        snapshot.state is KastMutationOperationState.Cancelled
                    ) {
                        return snapshot
                    }
                }
            }
            Thread.sleep(5)
            poll = pollMutationStatus(socketPath, selector)
        }
        error("Mutation operation did not become terminal after reconnect")
    }

    private fun pollMutationStatus(
        socketPath: Path,
        selector: KastMutationOperationSelector,
    ): MutationStatusPoll {
        val response = callSocket(
            socketPath = socketPath,
            request = JsonRpcRequest(
                id = JsonPrimitive(2),
                method = "mutation/status",
                params = json.encodeToJsonElement(KastMutationOperationSelector.serializer(), selector),
            ),
        )
        val responseObject = json.parseToJsonElement(response).jsonObject
        responseObject["result"]?.let {
            val success = json.decodeFromJsonElement(JsonRpcSuccessResponse.serializer(), responseObject)
            return MutationStatusPoll.Available(
                json.decodeFromJsonElement(KastMutationOperationSnapshot.serializer(), success.result),
            )
        }

        val failure = json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), responseObject)
        val errorData = failure.error.data
        check(
            failure.error.code == JSON_RPC_SERVER_ERROR_BASE - HTTP_NOT_FOUND_STATUS &&
                errorData?.code == NOT_FOUND_ERROR_CODE &&
                errorData.details.entries.containsAll(selector.expectedNotFoundDetails().entries),
        ) {
            "Unexpected mutation/status failure while awaiting admission: $response"
        }
        return MutationStatusPoll.NotAdmitted
    }

    private fun KastMutationOperationSelector.expectedNotFoundDetails(): Map<String, String> = when (this) {
        is KastMutationOperationSelector.ByOperationId -> mapOf("operationId" to operationId.value)
        is KastMutationOperationSelector.ByIdempotencyKey -> mapOf("idempotencyKey" to idempotencyKey.value)
    }

    private sealed interface MutationStatusPoll {
        data object NotAdmitted : MutationStatusPoll

        data class Available(
            val snapshot: KastMutationOperationSnapshot,
        ) : MutationStatusPoll
    }

    private fun awaitClientHandlerCompletion() {
        repeat(50) {
            Thread.sleep(10)
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND_STATUS = 404
        const val NOT_FOUND_ERROR_CODE = "NOT_FOUND"
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
}

private class ClosingApplyBackend(
    private val delegate: CloseableAnalysisBackend,
    private val applyStarted: CompletableDeferred<Unit>,
    private val applyStopped: CompletableDeferred<Unit>,
    private val descriptorFile: Path,
    private val descriptorIdentity: String,
    private val descriptorRetainedDuringStop: AtomicBoolean,
) : CloseableAnalysisBackend by delegate {
    override suspend fun applyEdits(query: ParsedApplyEditsQuery): ApplyEditsResult {
        applyStarted.complete(Unit)
        return try {
            delay(250)
            delegate.applyEdits(query)
        } finally {
            descriptorRetainedDuringStop.set(
                Files.exists(descriptorFile) && Files.readString(descriptorFile).contains(descriptorIdentity),
            )
            applyStopped.complete(Unit)
        }
    }
}

private class CountingCloseBackend(
    private val delegate: CloseableAnalysisBackend,
) : CloseableAnalysisBackend by delegate {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount += 1
        delegate.close()
    }
}

private class RecordingLocalRpcServer(
    private val closeEvents: MutableList<String>,
    private val closeFailure: Throwable? = null,
) : LocalRpcServer {
    override fun await() = Unit

    override fun close() {
        closeEvents += "transport"
        closeFailure?.let { throw it }
    }
}

private class RecordingCloseable(
    private val closeEvents: MutableList<String>,
    private val phase: String,
    private val closeFailure: Throwable? = null,
) : java.io.Closeable {
    override fun close() {
        closeEvents += phase
        closeFailure?.let { throw it }
    }
}

private class RecordingCloseBackend(
    private val delegate: CloseableAnalysisBackend,
    private val closeEvents: MutableList<String>,
    private val beforeClose: () -> Unit = {},
) : CloseableAnalysisBackend by delegate {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeEvents += "backend"
        beforeClose()
        closeCount += 1
        delegate.close()
    }
}
