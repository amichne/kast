package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("native")
class WireDeadlineTest {
    @Test
    fun `successful frame preserves its reusable connection and reports completion`() {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kwd-")
        val socket = root.resolve("s")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))
                SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                    client.connect(UnixDomainSocketAddress.of(socket))
                    server.accept().use { peer ->
                        val limit = (ElapsedTimeLimitMillis.parse(1_000) as Refinement.Refined).value
                        repeat(2) {
                            val deadline = WireIoDeadline(client, limit)
                            peer.write(ByteBuffer.wrap(byteArrayOf(42)))
                            val received = ByteBuffer.allocate(1)
                            assertEquals(1, client.read(received))
                            assertEquals(WireRequestOutcome.COMPLETED, deadline.finish())
                            assertTrue(client.isOpen)
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(socket)
            Files.deleteIfExists(root)
        }
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
                        val future = executor.submit<WireRequestOutcome> { readFrameUnderDeadline(client, limit) }
                        assertEquals(WireRequestOutcome.TIMED_OUT, future.get(2, TimeUnit.SECONDS))
                        assertFalse(client.isOpen, "expired blocking I/O must close its owned channel")
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

    private fun readFrameUnderDeadline(client: SocketChannel, limit: ElapsedTimeLimitMillis): WireRequestOutcome {
        val deadline = WireIoDeadline(client, limit)
        try {
            val frame = ByteBuffer.allocate(4)
            while (frame.hasRemaining()) if (client.read(frame) < 0) break
        } catch (_: java.io.IOException) {
            // Expiry closes the exact channel to unblock this read.
        }
        return deadline.finish()
    }
}
