package io.github.amichne.kast.runtime.hosted

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

internal enum class HostedPeerTermination {
    DISCONNECTED,
    EXTRA_INPUT,
}

internal sealed interface HostedPeerDispatch {
    data class Completed(val response: String) : HostedPeerDispatch

    data class Rejected(val termination: HostedPeerTermination) : HostedPeerDispatch
}

/** The request owns both jobs until cancellation cleanup has finished. */
internal suspend fun dispatchUntilPeerTermination(
    awaitTermination: suspend () -> HostedPeerTermination,
    dispatch: suspend () -> String,
): HostedPeerDispatch = coroutineScope {
    val peer = async { awaitTermination() }
    val work = async { dispatch() }
    try {
        select {
            peer.onAwait { HostedPeerDispatch.Rejected(it) }
            work.onAwait { HostedPeerDispatch.Completed(it) }
        }
    } finally {
        withContext(NonCancellable) {
            work.cancelAndJoin()
            peer.cancelAndJoin()
        }
    }
}

/** After the single frame, EOF cancels work and any second input fails the one-request contract. */
internal suspend fun SocketChannel.awaitHostedPeerTermination(): HostedPeerTermination =
    withContext(Dispatchers.IO) {
        configureBlocking(false)
        try {
            Selector.open().use { selector ->
                register(selector, SelectionKey.OP_READ)
                val probe = ByteBuffer.allocate(1)
                while (true) {
                    runInterruptible { selector.select() }
                    ensureActive()
                    selector.selectedKeys().clear()
                    when (read(probe)) {
                        -1 -> return@withContext HostedPeerTermination.DISCONNECTED
                        0 -> Unit
                        else -> return@withContext HostedPeerTermination.EXTRA_INPUT
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("Peer observation loop ended")
            }
        } finally {
            if (isOpen) configureBlocking(true)
        }
    }
