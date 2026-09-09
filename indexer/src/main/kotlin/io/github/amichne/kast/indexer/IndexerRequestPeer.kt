package io.github.amichne.kast.indexer

import io.github.amichne.kast.distribution.contract.IndexerTransportLimits
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

internal enum class IndexerPeerState { CONNECTED, DISCONNECTED, REJECTED }

internal fun interface IndexerRequestPeer {
    fun observe(): IndexerPeerState
    companion object {
        val Unobserved = IndexerRequestPeer { IndexerPeerState.CONNECTED }
    }
}

/** One connection worker observes EOF while its job runs and retains bounded pipelined input. */
internal class IndexerConnectionInput(private val channel: SocketChannel) : IndexerRequestPeer {
    private val pending = java.util.ArrayDeque<ByteBuffer>()
    private var pendingBytes = 0
    override fun observe(): IndexerPeerState {
        val buffer = ByteBuffer.allocate(4096)
        return try {
            channel.configureBlocking(false)
            when (val read = channel.read(buffer)) {
                -1 -> IndexerPeerState.DISCONNECTED
                0 -> IndexerPeerState.CONNECTED
                else -> {
                    if (read > IndexerTransportLimits.maximumFrameBytes + Int.SIZE_BYTES - pendingBytes)
                        return IndexerPeerState.REJECTED
                    pendingBytes += read
                    pending.addLast(buffer.flip())
                    IndexerPeerState.CONNECTED
                }
            }
        } catch (_: IOException) { IndexerPeerState.REJECTED }
    }

    fun read(buffer: ByteBuffer): Int {
        if (pending.isEmpty()) return channel.read(buffer)
        val first = pending.first
        val copied = minOf(first.remaining(), buffer.remaining())
        repeat(copied) { buffer.put(first.get()) }
        pendingBytes -= copied
        if (!first.hasRemaining()) pending.removeFirst()
        return copied
    }
}
