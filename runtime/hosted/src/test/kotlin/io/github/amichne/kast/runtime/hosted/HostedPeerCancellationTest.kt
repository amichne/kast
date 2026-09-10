@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.amichne.kast.runtime.hosted

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Transport ownership proof; this exercises no IntelliJ runtime or simulated semantic engine. */
class HostedPeerCancellationTest {
    @Test fun `disconnect drains semantic cancellation before the connection completes`() = runTest {
        val disconnected = CompletableDeferred<HostedPeerTermination>()
        val cleanup = CompletableDeferred<Unit>()
        val work = async {
            dispatchUntilPeerTermination(disconnected::await) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            }
        }
        runCurrent()
        disconnected.complete(HostedPeerTermination.DISCONNECTED)
        runCurrent()
        assertFalse(work.isCompleted)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(HostedPeerDispatch.Rejected(HostedPeerTermination.DISCONNECTED), work.await())
    }

    @Test fun `successful response drains its disconnect watcher`() = runTest {
        val watching = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val result = dispatchUntilPeerTermination(
            awaitTermination = {
                watching.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            },
        ) { watching.await(); "response" }
        assertEquals(HostedPeerDispatch.Completed("response"), result)
        assertTrue(stopped.isCompleted)
    }

    @Test fun `native peer watcher cancellation leaves the response channel writable`() = runBlocking {
        withTimeout(3_000) {
            java.nio.channels.ServerSocketChannel.open().use { listener ->
                listener.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                java.nio.channels.SocketChannel.open(listener.localAddress).use { client ->
                    listener.accept().use { server ->
                        val response = dispatchUntilPeerTermination(server::awaitHostedPeerTermination) {
                            while (server.isBlocking) delay(1)
                            "response"
                        }
                        assertEquals(HostedPeerDispatch.Completed("response"), response)
                        assertTrue(server.isBlocking)
                        assertTrue(server.isOpen)
                        HostedFrames.write(java.nio.channels.Channels.newOutputStream(server), "response")
                        val input = java.io.DataInputStream(java.nio.channels.Channels.newInputStream(client))
                        assertEquals("response", input.readNBytes(input.readInt()).toString(Charsets.UTF_8))
                    }
                }
            }
        }
    }
}
