package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Tag("native")
class WireDeadlineTest {
    @Test fun `successful frame preserves its reusable connection and reports completion`() {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kwd-")
        val socket = root.resolve("s")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))
                SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                    client.connect(UnixDomainSocketAddress.of(socket))
                    server.accept().use { peer ->
                        val events = mutableListOf<WireRequestActivity>()
                        val limit = (ElapsedTimeLimitMillis.parse(1_000) as Refinement.Refined).value
                        WireSession(client, limit, WireActivitySink { events.add(it) }).use { session ->
                            repeat(2) {
                                assertEquals(WireFrameWrite.Written, WireFrameCodec.write(peer, "{}"))
                                assertEquals(WireExchange.Received("{}"), session.exchange("{}"))
                                assertTrue(client.isOpen)
                            }
                            assertEquals(List(2) { WireRequestActivity(WireRequestStage.EXCHANGE, WireRequestOutcome.COMPLETED) }, events)
                        }
                    }
                }
            }
        } finally { Files.deleteIfExists(socket); Files.deleteIfExists(root) }
    }
    @Test fun `silent peer consumes one elapsed allowance and closes its owned socket`() = stalledPeer(false)
    @Test fun `partial frame cannot renew the elapsed allowance`() = stalledPeer(true)

    private fun stalledPeer(partial: Boolean) {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kwd-")
        val socket = root.resolve("s")
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))
                SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                    client.connect(UnixDomainSocketAddress.of(socket))
                    server.accept().use { peer ->
                        if (partial) peer.write(ByteBuffer.wrap(byteArrayOf(0)))
                        val limit = (ElapsedTimeLimitMillis.parse(75) as Refinement.Refined).value
                        val events = mutableListOf<WireRequestActivity>()
                        WireSession(client, limit, WireActivitySink { events.add(it) }).use { session ->
                            val future = executor.submit<WireExchange> { session.exchange("{}") }
                            val result = try { future.get(2, TimeUnit.SECONDS) }
                            catch (_: TimeoutException) { null }
                            assertNotNull(result, "the request did not terminate after its injected elapsed allowance")
                            assertEquals(WireExchange.Rejected(WireTransportFailure.TIMED_OUT), result)
                            assertEquals(listOf(WireRequestActivity(WireRequestStage.EXCHANGE, WireRequestOutcome.TIMED_OUT)), events)
                            assertFalse(client.isOpen, "expired blocking I/O must close its owned channel")
                        }
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            Files.deleteIfExists(socket)
            Files.deleteIfExists(root)
        }
    }
}
