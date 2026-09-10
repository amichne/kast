package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/** Real local socket ownership proof; no IntelliJ application or semantic fixture is started. */
class HostedListenerTest {
    @Test
    fun `blocked native accept cancellation drains with cancelled observation`() = runTest(timeout = 5.seconds) {
        ServerSocketChannel.open().use { listener ->
            listener.bind(InetSocketAddress("127.0.0.1", 0))
            val started = CompletableDeferred<Unit>()
            val observations = observations()
            val accepting = async(Dispatchers.IO) {
                acceptHostedConnection(observer(observations), dispatcher = Dispatchers.IO) {
                    started.complete(Unit)
                    listener.accept()
                }
            }
            started.await()
            accepting.cancel()
            assertInstanceOf(CancellationException::class.java, runCatching { accepting.await() }.exceptionOrNull())
            assertTrue(accepting.isCompleted)
            assertFalse(listener.isOpen)
            assertEquals(events(HostedEndpointOutcome.CANCELLED), observations.toList())
        }
    }

    @Test
    fun `closed native listener rejects with finite accept observations`() = runTest(timeout = 5.seconds) {
        val listener = ServerSocketChannel.open()
        listener.close()
        val observations = observations()

        val result = acceptHostedConnection(observer(observations), dispatcher = Dispatchers.IO, accept = listener::accept)

        assertEquals(Refinement.Rejected(HostedEndpointFailure.IO_UNAVAILABLE), result)
        assertEquals(events(HostedEndpointOutcome.REJECTED), observations.toList())
    }

    @Test
    fun `native accepted connection transfers open ownership with completed observation`() = runTest(timeout = 5.seconds) {
        withContext(Dispatchers.IO) {
            ServerSocketChannel.open().use { listener ->
                listener.bind(InetSocketAddress("127.0.0.1", 0))
                SocketChannel.open(listener.localAddress).use {
                    val observations = observations()
                    val accepted = acceptHostedConnection(observer(observations), dispatcher = Dispatchers.IO, accept = listener::accept)
                    assertTrue(accepted is Refinement.Refined)
                    (accepted as Refinement.Refined).value.use { connection ->
                        assertTrue(connection.isOpen)
                        assertTrue(connection.isConnected)
                    }
                    assertEquals(events(HostedEndpointOutcome.COMPLETED), observations.toList())
                }
            }
        }
    }

    @Test
    fun `cancellation after native accept closes the connection before ownership transfer`() = runTest(timeout = 5.seconds) {
        withContext(Dispatchers.IO) {
            ServerSocketChannel.open().use { listener ->
                listener.bind(InetSocketAddress("127.0.0.1", 0))
                SocketChannel.open(listener.localAddress).use {
                    val observations = observations()
                    val nativeConnection = AtomicReference<SocketChannel>()
                    try {
                        val accepting = async {
                            val job = currentCoroutineContext().job
                            acceptHostedConnection(observer(observations), dispatcher = Dispatchers.IO) {
                                listener.accept().also { connection ->
                                    nativeConnection.set(connection)
                                    job.cancel()
                                }
                            }
                        }
                        assertInstanceOf(CancellationException::class.java, runCatching { accepting.await() }.exceptionOrNull())
                        assertTrue(accepting.isCompleted)
                        assertFalse(nativeConnection.get().isOpen)
                        assertEquals(events(HostedEndpointOutcome.CANCELLED), observations.toList())
                    } finally {
                        nativeConnection.get()?.close()
                    }
                }
            }
        }
    }

    private fun observations(): MutableList<Pair<HostedEndpointStage, HostedEndpointOutcome>> =
        Collections.synchronizedList(mutableListOf())

    private fun observer(observations: MutableList<Pair<HostedEndpointStage, HostedEndpointOutcome>>) =
        HostedEndpointObserver { stage, outcome -> observations += stage to outcome }

    private fun events(terminal: HostedEndpointOutcome) = listOf(
        HostedEndpointStage.ACCEPT to HostedEndpointOutcome.STARTED,
        HostedEndpointStage.ACCEPT to terminal,
    )
}
