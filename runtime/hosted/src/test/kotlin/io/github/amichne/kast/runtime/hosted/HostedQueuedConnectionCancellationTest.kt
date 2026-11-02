package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueuedConnectionCancellationTest {
    @Test
    fun `listener cancellation closes queued peer without dispatch or an IO failure`() =
        runTest(timeout = 10.seconds) {
            val events = Collections.synchronizedList(mutableListOf<HostedTransportObservation>())
            val queued = CompletableDeferred<HostedTransportObservation>()
            val dispatches = AtomicInteger()
            val limits = (ReadLimits.resolve(mapOf("KAST_READ_HOST_CONNECTIONS" to "1")) as Refinement.Refined).value
            val observer = cancellationObserver(events, queued)
            fixture { listener, address ->
                // The shared scheduler lets admission suspend before cancellation releases the occupied permit.
                val server = launch {
                    serveHostedListener(listener, observer, limits) { _, _ ->
                        dispatches.incrementAndGet()
                        error("No request reached dispatch")
                    }
                }
                try {
                    SocketChannel.open(StandardProtocolFamily.UNIX).use { active ->
                        SocketChannel.open(StandardProtocolFamily.UNIX).use { waiting ->
                            withContext(Dispatchers.IO) {
                                active.connect(address)
                                waiting.connect(address)
                            }
                            val admission = queued.await()
                            assertEquals(
                                0,
                                synchronized(events) {
                                    events.count {
                                        it.connectionId == admission.connectionId &&
                                            it.stage == HostedTransportStage.CONNECTION_ADMISSION &&
                                            it.outcome == HostedEndpointOutcome.COMPLETED
                                    }
                                },
                            )
                            server.cancelAndJoin()
                            assertEquals(-1, withContext(Dispatchers.IO) { waiting.read(ByteBuffer.allocate(1)) })
                        }
                    }
                } finally {
                    server.cancelAndJoin()
                }
            }
            assertCancelledAdmission(events, queued.await())
            assertEquals(0, dispatches.get())
        }

    @Test
    fun `cancelled admission rejects a slot released by the active peer`() = runBlocking {
        withTimeout(10_000) {
            val events = Collections.synchronizedList(mutableListOf<HostedTransportObservation>())
            val queued = CompletableDeferred<HostedTransportObservation>()
            val activeReleased = CompletableDeferred<Unit>()
            val resumeAdmission = CountDownLatch(1)
            val dispatches = AtomicInteger()
            val observer =
                object : HostedEndpointObserver {
                    private var admissions = 0

                    override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) = Unit

                    override fun transport(observation: HostedTransportObservation) {
                        events.add(observation)
                        if (
                            observation.stage == HostedTransportStage.CONNECTION_RELEASE &&
                                observation.outcome == HostedEndpointOutcome.COMPLETED
                        )
                            activeReleased.complete(Unit)
                        if (
                            observation.stage == HostedTransportStage.CONNECTION_ADMISSION &&
                                observation.outcome == HostedEndpointOutcome.STARTED &&
                                ++admissions == 2
                        ) {
                            queued.complete(observation)
                            assertTrue(resumeAdmission.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
            val limits = (ReadLimits.resolve(mapOf("KAST_READ_HOST_CONNECTIONS" to "1")) as Refinement.Refined).value
            fixture { listener, address ->
                val server =
                    launch(Dispatchers.IO) {
                        serveHostedListener(listener, observer, limits) { _, _ ->
                            dispatches.incrementAndGet()
                            error("No request reached dispatch")
                        }
                    }
                try {
                    SocketChannel.open(StandardProtocolFamily.UNIX).use { active ->
                        SocketChannel.open(StandardProtocolFamily.UNIX).use { waiting ->
                            withContext(Dispatchers.IO) {
                                active.connect(address)
                                waiting.connect(address)
                            }
                            queued.await()
                            server.cancel()
                            activeReleased.await()
                            resumeAdmission.countDown()
                            server.join()
                            assertEquals(-1, withContext(Dispatchers.IO) { waiting.read(ByteBuffer.allocate(1)) })
                        }
                    }
                } finally {
                    resumeAdmission.countDown()
                    server.cancelAndJoin()
                }
            }
            assertCancelledAdmission(events, queued.await())
            assertEquals(0, dispatches.get())
        }
    }

    private fun assertCancelledAdmission(
        events: List<HostedTransportObservation>,
        queued: HostedTransportObservation,
    ) {
        val ended =
            events
                .filter { it.stage == HostedTransportStage.CONNECTION_ADMISSION }
                .single {
                    it.outcome != HostedEndpointOutcome.STARTED && it.outcome != HostedEndpointOutcome.COMPLETED
                }
        assertEquals(queued.connectionId, ended.connectionId)
        assertEquals(HostedEndpointOutcome.CANCELLED, ended.outcome)
        assertNull(ended.failure)
    }

    private fun cancellationObserver(
        events: MutableList<HostedTransportObservation>,
        queued: CompletableDeferred<HostedTransportObservation>,
    ) =
        object : HostedEndpointObserver {
            private var admissions = 0

            override fun observe(stage: HostedEndpointStage, outcome: HostedEndpointOutcome) = Unit

            override fun transport(observation: HostedTransportObservation) {
                events.add(observation)
                if (
                    observation.stage == HostedTransportStage.CONNECTION_ADMISSION &&
                        observation.outcome == HostedEndpointOutcome.STARTED &&
                        ++admissions == 2
                )
                    queued.complete(observation)
            }
        }

    private suspend fun fixture(block: suspend (ServerSocketChannel, UnixDomainSocketAddress) -> Unit) {
        val directory = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kqc-")
        val path = directory.resolve("host.sock")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                val address = UnixDomainSocketAddress.of(path)
                listener.bind(address, 2)
                block(listener, address)
            }
        } finally {
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }
}
