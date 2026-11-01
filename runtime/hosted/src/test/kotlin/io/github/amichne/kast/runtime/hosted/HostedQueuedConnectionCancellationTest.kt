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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HostedQueuedConnectionCancellationTest {
    @Test
    fun `listener cancellation closes queued peer without dispatch or an IO failure`() = runBlocking {
        withTimeout(10_000) {
            val events = Collections.synchronizedList(mutableListOf<HostedTransportObservation>())
            val queued = CompletableDeferred<Unit>()
            val dispatches = AtomicInteger()
            val limits = (ReadLimits.resolve(mapOf("KAST_READ_HOST_CONNECTIONS" to "1")) as Refinement.Refined).value
            val observer = cancellationObserver(events, queued)
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
                            server.cancelAndJoin()
                            assertEquals(-1, withContext(Dispatchers.IO) { waiting.read(ByteBuffer.allocate(1)) })
                        }
                    }
                } finally {
                    server.cancelAndJoin()
                }
            }
            val ended =
                events
                    .filter { it.stage == HostedTransportStage.CONNECTION_ADMISSION }
                    .single {
                        it.outcome != HostedEndpointOutcome.STARTED && it.outcome != HostedEndpointOutcome.COMPLETED
                    }
            assertEquals(HostedEndpointOutcome.CANCELLED, ended.outcome)
            assertNull(ended.failure)
            assertEquals(0, dispatches.get())
        }
    }

    private fun cancellationObserver(
        events: MutableList<HostedTransportObservation>,
        queued: CompletableDeferred<Unit>,
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
                    queued.complete(Unit)
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
