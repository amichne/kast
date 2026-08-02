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
import io.github.amichne.kast.api.client.SocketFileIdentity
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import io.github.amichne.kast.api.contract.mutation.KastMutationExecutionResult
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.skill.KastAddFileRequest
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists

class AnalysisServerOwnershipTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
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
        val socketPath = tempDir.resolve("d.sock")
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
            workspaceRoot = RuntimeWorkspaceRoot.canonicalize(tempDir),
            backendVersion = RuntimeImplementationVersion("test"),
            socketPath = RuntimeSocketPath.of(tempDir.resolve("failure.sock")),
            ownership = ServerInstanceOwnership.LegacyWithoutProcessId,
        )
        val descriptorStore = DescriptorStore(DescriptorRegistryPath.of(descriptorFile)).also { it.write(descriptor) }
        val closeEvents = mutableListOf<String>()
        val transportFailure = IllegalStateException("transport close failed")
        val backend = RecordingCloseBackend(
            delegate = FakeAnalysisBackend.sample(tempDir),
            closeEvents = closeEvents,
            beforeClose = {
                assertTrue(Files.readString(descriptorFile).contains(descriptor.socketPath.value))
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
            Files.exists(descriptorFile) && Files.readString(descriptorFile).contains(descriptor.socketPath.value),
            "descriptor cleanup was skipped after an earlier close failure",
        )
    }

    @Test
    fun `closing an old instance preserves a replacement descriptor`() {
        val descriptorFile = tempDir.resolve("replacement-instances").resolve("daemons.json")
        val first = ServerInstanceDescriptor(
            workspaceRoot = RuntimeWorkspaceRoot.canonicalize(tempDir),
            backendVersion = RuntimeImplementationVersion("test"),
            socketPath = RuntimeSocketPath.of(tempDir.resolve("headless.sock")),
            ownership = ServerInstanceOwnership.Owned(
                runtimeInstanceId = RuntimeInstanceId.create(),
                processIdentity = RuntimeProcessIdentity(
                    processId = ProcessId.current(),
                    processStartEpochMillis = ProcessStartEpochMillis.of(1),
                ),
                ownerUid = SocketOwnerUid.of(501),
                socketFileIdentity = SocketFileIdentity(device = 1, inode = 1),
            ),
        )
        val firstOwner = first.ownership as ServerInstanceOwnership.Owned
        val replacementOwner = firstOwner.copy(runtimeInstanceId = RuntimeInstanceId.create())
        val replacement = first.copy(ownership = replacementOwner)
        val descriptorStore = DescriptorStore(DescriptorRegistryPath.of(descriptorFile)).also {
            it.write(first)
            it.write(replacement)
        }
        val runningServer = RunningAnalysisServer(
            server = RecordingLocalRpcServer(mutableListOf()),
            dispatcher = RecordingCloseable(mutableListOf(), "dispatcher"),
            backend = CountingCloseBackend(FakeAnalysisBackend.sample(tempDir)),
            descriptor = first,
            descriptorStore = descriptorStore,
        )

        runningServer.close()

        assertTrue(Files.readString(descriptorFile).contains(replacementOwner.runtimeInstanceId.value))
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
