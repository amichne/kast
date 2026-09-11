package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** One native accept owns its connection until active coroutine ownership can be transferred. */
internal suspend fun acceptHostedConnection(
    observer: HostedEndpointObserver,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    accept: () -> SocketChannel?,
): Refinement<SocketChannel, HostedEndpointFailure> {
    val pending = AtomicReference<SocketChannel?>()
    observer.observe(HostedEndpointStage.ACCEPT, HostedEndpointOutcome.STARTED)
    try {
        val connection =
            try {
                runInterruptible(dispatcher) { accept().also(pending::set) }
            } catch (_: IOException) {
                // NIO interruption closes a channel with IOException, not InterruptedException.
                currentCoroutineContext().ensureActive()
                observer.observe(HostedEndpointStage.ACCEPT, HostedEndpointOutcome.REJECTED)
                return Refinement.Rejected(HostedEndpointFailure.IO_UNAVAILABLE)
            }
        currentCoroutineContext().ensureActive()
        if (connection == null) {
            observer.observe(HostedEndpointStage.ACCEPT, HostedEndpointOutcome.REJECTED)
            return Refinement.Rejected(HostedEndpointFailure.IO_UNAVAILABLE)
        }
        observer.observe(HostedEndpointStage.ACCEPT, HostedEndpointOutcome.COMPLETED)
        pending.set(null)
        return Refinement.Refined(connection)
    } catch (cancelled: CancellationException) {
        try {
            pending.getAndSet(null)?.close()
        } catch (cleanup: IOException) {
            cancelled.addSuppressed(cleanup)
        }
        observer.observe(HostedEndpointStage.ACCEPT, HostedEndpointOutcome.CANCELLED)
        throw cancelled
    }
}
