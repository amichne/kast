package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Real socket EOF cancels the production SDK status read; no semantic evaluator is supplied. */
class NativePeerDisconnectTest : NativeSmartModeFixtureTest() {
    fun testPeerDisconnectDrainsNativeReadBeforeConnectionRelease() = runTest {
        withNativeCaller {
            Executors.newFixedThreadPool(SOCKET_CALLER_THREADS).asCoroutineDispatcher().use { io ->
                withNativeSocket { listener, address ->
                    val records = PeerWaitRecords()
                    val server = startListener(listener, io, records)
                    try {
                        withNativeWriteHeld { write ->
                            disconnectAndCheckDrainage(address, io, records, write)
                            assertTrue(server.isActive)
                        }
                        assertEquals(
                            readyDocument(),
                            withContext(io) {
                                nativeExchange(address, requestDocument("/workspace/fresh"))
                            },
                        )
                    } finally {
                        server.cancelAndJoin()
                    }
                    val waits = synchronized(records.waits) { records.waits.toList() }
                    assertEquals(2, waits.map { it.connectionId }.toSet().size)
                    assertEquals(
                        listOf(
                            HostedSmartModeWaitOutcome.STARTED,
                            HostedSmartModeWaitOutcome.CANCELLED,
                            HostedSmartModeWaitOutcome.STARTED,
                            HostedSmartModeWaitOutcome.READY,
                        ),
                        waits.map { it.wait.outcome },
                    )
                }
            }
        }
    }

    private fun CoroutineScope.startListener(
        listener: ServerSocketChannel,
        io: CoroutineDispatcher,
        records: PeerWaitRecords,
    ) =
        launch(io) {
            val limits = (ReadLimits.resolve(mapOf("KAST_READ_HOST_CONNECTIONS" to "1")) as Refinement.Refined).value
            serveHostedListener(listener, records, limits) { _, trace ->
                coroutineScope {
                    // The SDK call suspends before the first peer is told it may disconnect.
                    val wait =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            awaitHostedSmartMode(project, trace::smartModeWait)
                        }
                    if (!records.submitted.isCompleted) {
                        assertFalse(wait.isCompleted)
                        records.submitted.complete(Unit)
                    }
                    assertEquals(IndexingWait.Ready, wait.await())
                    HostedResponse.Completed(readyDocument())
                }
            }
        }

    private suspend fun disconnectAndCheckDrainage(
        address: UnixDomainSocketAddress,
        io: CoroutineDispatcher,
        records: PeerWaitRecords,
        write: NativeWriteBarrier,
    ) {
        withContext(io) {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { peer ->
                peer.connect(address)
                writeNativeRequest(peer, requestDocument("/workspace/native"))
                records.submitted.await()
            }
        }
        val connectionId = records.released.await()
        val first = synchronized(records.waits) { records.waits.filter { it.connectionId == connectionId } }
        assertEquals(
            listOf(HostedSmartModeWaitOutcome.STARTED, HostedSmartModeWaitOutcome.CANCELLED),
            first.map { it.wait.outcome },
        )
        assertEquals(1L, first.last().wait.statusPolls)
        assertTrue(write.job.isActive)
        assertEquals(1L, write.release.count)
        val rejection = withContext(io) { nativeExchange(address, "invalid request") }
        assertEquals(
            NativeRejectedReply(HostedEndpointFailure.INVALID_REQUEST, NativeRejectionType.HOST_REJECTED),
            nativeJson.decodeFromString<NativeRejectedReply>(rejection),
        )
        assertTrue(write.job.isActive)
        assertEquals(1L, write.release.count)
    }

    private suspend fun withNativeSocket(block: suspend (ServerSocketChannel, UnixDomainSocketAddress) -> Unit) {
        val directory = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kswp-")
        val socket = directory.resolve("host.sock")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                val address = UnixDomainSocketAddress.of(socket)
                listener.bind(address)
                block(listener, address)
            }
        } finally {
            Files.deleteIfExists(socket)
            Files.delete(directory)
        }
    }

    private fun writeNativeRequest(peer: SocketChannel, document: String) {
        val request = document.toByteArray(Charsets.UTF_8)
        DataOutputStream(Channels.newOutputStream(peer)).apply {
            writeInt(request.size)
            write(request)
            flush()
        }
    }

    private fun nativeExchange(address: UnixDomainSocketAddress, document: String): String =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { peer ->
            peer.connect(address)
            writeNativeRequest(peer, document)
            val input = DataInputStream(Channels.newInputStream(peer))
            val size = input.readInt()
            assertTrue(size in 1..MAX_NATIVE_REPLY_BYTES)
            val reply = input.readNBytes(size)
            assertEquals(size, reply.size)
            assertEquals(-1, input.read())
            reply.toString(Charsets.UTF_8)
        }

    private class PeerWaitRecords : HostedEndpointObserver {
        val submitted = CompletableDeferred<Unit>()
        val released = CompletableDeferred<String>()
        val waits: MutableList<HostedCorrelatedSmartModeWait> = Collections.synchronizedList(mutableListOf())

        override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) = Unit

        override fun smartModeWait(observation: HostedCorrelatedSmartModeWait) {
            waits += observation
        }

        override fun transport(observation: HostedTransportObservation) {
            if (
                observation.stage == HostedTransportStage.CONNECTION_RELEASE &&
                    observation.outcome == HostedEndpointOutcome.COMPLETED
            ) {
                released.complete(observation.connectionId)
            }
        }
    }

    @Serializable private data class NativeRequest(val root: String, val type: String = "DESCRIBE")

    @Serializable
    private enum class NativeReplyOutcome {
        READY
    }

    @Serializable private data class NativeReadyReply(val outcome: NativeReplyOutcome)

    @Serializable
    private enum class NativeRejectionType {
        HOST_REJECTED
    }

    @Serializable
    private data class NativeRejectedReply(val failure: HostedEndpointFailure, val type: NativeRejectionType)

    private companion object {
        const val MAX_NATIVE_REPLY_BYTES = 65_536
        const val SOCKET_CALLER_THREADS = 2
        val nativeJson = Json { encodeDefaults = true }

        fun readyDocument() = nativeJson.encodeToString(NativeReadyReply(NativeReplyOutcome.READY))

        fun requestDocument(root: String) = nativeJson.encodeToString(NativeRequest(root))
    }
}
