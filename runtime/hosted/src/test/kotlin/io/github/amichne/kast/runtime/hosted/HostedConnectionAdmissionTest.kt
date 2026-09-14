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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Native transport fixtures deliberately make no claim about compiler/IDE semantic correctness. */
class HostedConnectionAdmissionTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `156 correlated reads finish with a blocked frame and serialized dispatch`() = runBlocking {
        withTimeout(30_000) {
            fixture { listener, address ->
                val observations = Collections.synchronizedList(mutableListOf<HostedTransportObservation>())
                val blocked = CompletableDeferred<Unit>()
                val observer =
                    observer(observations) { event ->
                        if (
                            event.stage == HostedTransportStage.REQUEST_READ &&
                                event.outcome == HostedEndpointOutcome.STARTED
                        ) {
                            blocked.complete(Unit)
                        }
                    }
                val active = AtomicInteger()
                val calls = AtomicInteger()
                val server =
                    launch(Dispatchers.IO) {
                        serveHostedListener(listener, observer, ReadLimits.Default) { request ->
                            assertEquals(1, active.incrementAndGet())
                            try {
                                yield()
                                calls.incrementAndGet()
                                HostedResponse.Completed(json.encodeToString(Reply(request.root.value)))
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                    }
                try {
                    SocketChannel.open(StandardProtocolFamily.UNIX).use { slow ->
                        withContext(Dispatchers.IO) { slow.connect(address) }
                        blocked.await()
                        concurrentReads(address)
                    }
                    assertEquals(156, calls.get())
                } finally {
                    server.cancelAndJoin()
                }
                assertReplayObservations(observations)
            }
        }
    }

    @Test
    fun `saturated admission returns one complete finite rejection without dispatch`() = runBlocking {
        withTimeout(10_000) {
            fixture { listener, address ->
                val reading = CompletableDeferred<Unit>()
                val observations = Collections.synchronizedList(mutableListOf<HostedTransportObservation>())
                val limits =
                    (ReadLimits.resolve(environment = mapOf("KAST_READ_HOST_CONNECTIONS" to "1")) as Refinement.Refined)
                        .value
                val server =
                    launch(Dispatchers.IO) {
                        serveHostedListener(
                            listener,
                            observer(observations) {
                                if (
                                    it.stage == HostedTransportStage.REQUEST_READ &&
                                        it.outcome == HostedEndpointOutcome.STARTED
                                )
                                    reading.complete(Unit)
                            },
                            limits,
                        ) {
                            error("Saturated requests must not dispatch")
                        }
                    }
                try {
                    SocketChannel.open(StandardProtocolFamily.UNIX).use { slow ->
                        withContext(Dispatchers.IO) { slow.connect(address) }
                        reading.await()
                        val response = withContext(Dispatchers.IO) { exchange(address, "/workspace") }
                        assertEquals(
                            HostedRequests.rejected(HostedEndpointFailure.ADMISSION_CAPACITY_EXCEEDED),
                            response,
                        )
                    }
                    assertTrue(
                        synchronized(observations) { observations.toList() }
                            .any {
                                it.stage == HostedTransportStage.SEMANTIC_ADMISSION &&
                                    it.failure == HostedEndpointFailure.ADMISSION_CAPACITY_EXCEEDED
                            }
                    )
                } finally {
                    server.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `disconnected dispatch drains before another request enters and listener remains healthy`() = runBlocking {
        withTimeout(10_000) {
            fixture { listener, address ->
                val cancellation = CancellationFixture()
                val server =
                    launch(Dispatchers.IO) {
                        serveHostedListener(listener, cancellation, ReadLimits.Default, cancellation::dispatch)
                    }
                try {
                    withContext(Dispatchers.IO) {
                        SocketChannel.open(StandardProtocolFamily.UNIX).use { peer ->
                            peer.connect(address)
                            val request = json.encodeToString(Request("/workspace/cancel")).toByteArray()
                            DataOutputStream(Channels.newOutputStream(peer)).apply {
                                writeInt(request.size)
                                write(request)
                                flush()
                            }
                            cancellation.entered.await()
                        }
                    }
                    cancellation.cleaning.await()
                    val next = async(Dispatchers.IO) { exchange(address, "/workspace/next") }
                    cancellation.nextQueued.await()
                    assertFalse(cancellation.nextEntered.isCompleted)
                    cancellation.release.complete(Unit)
                    assertEquals(json.encodeToString(Reply("/workspace/next")), next.await())
                    assertTrue(server.isActive)
                } finally {
                    cancellation.release.complete(Unit)
                    server.cancelAndJoin()
                }
                assertEquals(2, cancellation.released.get())
            }
        }
    }

    @Test
    fun `malformed request releases its admitted connection with a correlated completion signal`() = runBlocking {
        withTimeout(10_000) {
            fixture { listener, address ->
                val observations = Collections.synchronizedList(mutableListOf<HostedTransportObservation>())
                val server =
                    launch(Dispatchers.IO) {
                        serveHostedListener(listener, observer(observations) {}, ReadLimits.Default) {
                            error("Malformed input must not enter semantic dispatch")
                        }
                    }
                try {
                    assertEquals(
                        HostedRequests.rejected(HostedEndpointFailure.INVALID_REQUEST),
                        withContext(Dispatchers.IO) { exchangeRaw(address, "{") },
                    )
                } finally {
                    server.cancelAndJoin()
                }
                val events = synchronized(observations) { observations.toList() }
                val rejected = events.single {
                    it.stage == HostedTransportStage.REQUEST_READ && it.failure == HostedEndpointFailure.INVALID_REQUEST
                }
                assertEquals(
                    1,
                    events.count {
                        it.connectionId == rejected.connectionId &&
                            it.stage == HostedTransportStage.CONNECTION_RELEASE &&
                            it.outcome == HostedEndpointOutcome.COMPLETED
                    },
                )
            }
        }
    }

    private suspend fun concurrentReads(address: UnixDomainSocketAddress) = coroutineScope {
        val start = CyclicBarrier(12)
        (0 until 12)
            .map { client ->
                async(Dispatchers.IO) {
                    start.await()
                    repeat(13) { call ->
                        val root = "/workspace/client-$client/call-$call"
                        assertEquals(json.encodeToString(Reply(root)), exchange(address, root))
                    }
                }
            }
            .awaitAll()
    }

    private fun assertReplayObservations(observations: MutableList<HostedTransportObservation>) {
        val snapshot = synchronized(observations) { observations.toList() }
        val written = snapshot.filter {
            it.stage == HostedTransportStage.REPLY_WRITE && it.outcome == HostedEndpointOutcome.COMPLETED
        }
        assertEquals(156, written.size)
        assertEquals(156, written.map { it.connectionId }.distinct().size)
        assertTrue(written.all { it.bytes > 4 && it.elapsedNanos >= 0 })
        for (reply in written) {
            val events = snapshot.filter { it.connectionId == reply.connectionId }
            assertEquals(HostedTransportStage.entries.toSet(), events.map { it.stage }.toSet())
            assertTrue(events.none { it.outcome == HostedEndpointOutcome.REJECTED })
        }
    }

    private suspend fun fixture(block: suspend (ServerSocketChannel, UnixDomainSocketAddress) -> Unit) {
        val directory = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kar-")
        val path = directory.resolve("host.sock")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                val address = UnixDomainSocketAddress.of(path)
                listener.bind(address, 64)
                block(listener, address)
            }
        } finally {
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }

    private fun exchange(address: UnixDomainSocketAddress, root: String): String =
        exchangeRaw(address, json.encodeToString(Request(root)))

    private fun exchangeRaw(address: UnixDomainSocketAddress, document: String): String =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
            client.connect(address)
            val request = document.toByteArray(Charsets.UTF_8)
            DataOutputStream(Channels.newOutputStream(client)).apply {
                writeInt(request.size)
                write(request)
                flush()
            }
            val input = DataInputStream(Channels.newInputStream(client))
            val size = input.readInt()
            assertTrue(size in 1..65_536)
            val reply = input.readNBytes(size)
            assertEquals(size, reply.size)
            assertEquals(-1, input.read(), "A request owns exactly one response")
            reply.toString(Charsets.UTF_8)
        }

    private fun observer(
        events: MutableList<HostedTransportObservation>,
        signal: (HostedTransportObservation) -> Unit,
    ) =
        object : HostedEndpointObserver {
            override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) = Unit

            override fun transport(observation: HostedTransportObservation) {
                events.add(observation)
                signal(observation)
            }
        }

    private inner class CancellationFixture : HostedEndpointObserver {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val nextQueued = CompletableDeferred<Unit>()
        val nextEntered = CompletableDeferred<Unit>()
        private val admissions = AtomicInteger()
        val released = AtomicInteger()

        override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) = Unit

        override fun transport(observation: HostedTransportObservation) {
            if (
                observation.stage == HostedTransportStage.CONNECTION_RELEASE &&
                    observation.outcome == HostedEndpointOutcome.COMPLETED
            )
                released.incrementAndGet()
            if (
                observation.stage == HostedTransportStage.SEMANTIC_ADMISSION &&
                    observation.outcome == HostedEndpointOutcome.STARTED &&
                    admissions.incrementAndGet() == 2
            )
                nextQueued.complete(Unit)
        }

        suspend fun dispatch(request: HostedRequest): HostedResponse {
            if (request.root.value == "/workspace/cancel") {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        release.await()
                    }
                }
            }
            nextEntered.complete(Unit)
            return HostedResponse.Completed(json.encodeToString(Reply(request.root.value)))
        }
    }

    @Serializable private data class Request(val root: String, val type: String = "DESCRIBE")

    @Serializable private data class Reply(val root: String)
}
