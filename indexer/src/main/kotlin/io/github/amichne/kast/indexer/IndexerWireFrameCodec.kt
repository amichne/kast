package io.github.amichne.kast.indexer

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val MAX_INDEXER_FRAME_BYTES =
    io.github.amichne.kast.distribution.contract.IndexerTransportLimits.maximumFrameBytes

sealed interface IndexerFrameRead {
    data class Received(val document: String) : IndexerFrameRead

    data object EndOfStream : IndexerFrameRead

    data object Rejected : IndexerFrameRead

    data object TimedOut : IndexerFrameRead
}

sealed interface IndexerFrameWrite {
    data object Written : IndexerFrameWrite

    data object Rejected : IndexerFrameWrite

    data object TimedOut : IndexerFrameWrite
}

/** Bounded length-prefixed UTF-8 framing at the installed host boundary. */
internal object IndexerWireFrameCodec {
    /**
     * Proof transition: `SocketChannel -> IndexerFrameRead`.
     *
     * Establishes one complete frame of at most eight MiB or a clean connection-end state before another frame begins.
     * Rejection is closed by [IndexerFrameRead.Rejected]. Raw bytes leave only as the received boundary document.
     */
    fun read(
        channel: SocketChannel,
        limit: ElapsedTimeLimitMillis = IndexerRequestPolicy.Default.frameRead,
        input: IndexerConnectionInput = IndexerConnectionInput(channel),
    ): IndexerFrameRead {
        val deadline = IndexerFrameDeadline(limit)
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
        when (readCompletely(channel, header, deadline, input)) {
            BufferRead.Complete -> Unit
            BufferRead.EndOfStream -> return IndexerFrameRead.EndOfStream
            BufferRead.Rejected -> return IndexerFrameRead.Rejected
            BufferRead.TimedOut -> return IndexerFrameRead.TimedOut
        }
        header.flip()
        val length = header.int
        if (length !in 0..MAX_INDEXER_FRAME_BYTES) return IndexerFrameRead.Rejected
        val payload = ByteBuffer.allocate(length)
        when (readCompletely(channel, payload, deadline, input)) {
            BufferRead.Complete -> Unit
            BufferRead.EndOfStream,
            BufferRead.Rejected -> return IndexerFrameRead.Rejected
            BufferRead.TimedOut -> return IndexerFrameRead.TimedOut
        }
        payload.flip()
        return try {
            IndexerFrameRead.Received(
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(payload)
                    .toString()
            )
        } catch (_: CharacterCodingException) {
            IndexerFrameRead.Rejected
        }
    }

    /**
     * Proof transition: `SocketChannel + String -> IndexerFrameWrite`.
     *
     * Establishes that one response of at most eight MiB was completely written. Rejection is closed by
     * [IndexerFrameWrite.Rejected]. Raw bytes remain inside this transport adapter.
     */
    fun write(
        channel: SocketChannel,
        document: String,
        limit: ElapsedTimeLimitMillis = IndexerRequestPolicy.Default.frameWrite,
    ): IndexerFrameWrite {
        if (document.length > MAX_INDEXER_FRAME_BYTES) return IndexerFrameWrite.Rejected
        val payload = document.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_INDEXER_FRAME_BYTES) return IndexerFrameWrite.Rejected
        val frame = ByteBuffer.allocate(Int.SIZE_BYTES + payload.size).putInt(payload.size).put(payload).flip()
        return try {
            val deadline = IndexerFrameDeadline(limit)
            withNonBlockingChannel(channel) {
                Selector.open().use { selector ->
                    channel.register(selector, SelectionKey.OP_WRITE)
                    while (frame.hasRemaining()) {
                        if (Thread.currentThread().isInterrupted) return IndexerFrameWrite.Rejected
                        if (deadline.remainingMillis() <= 0) return IndexerFrameWrite.TimedOut
                        if (channel.write(frame) == 0) {
                            selector.select(deadline.remainingMillis().coerceAtLeast(1))
                            selector.selectedKeys().clear()
                        }
                    }
                }
            }
            IndexerFrameWrite.Written
        } catch (_: IOException) {
            IndexerFrameWrite.Rejected
        } catch (_: CancelledKeyException) {
            IndexerFrameWrite.Rejected
        }
    }

    /**
     * Proof transition: `SocketChannel + ByteBuffer -> BufferRead`.
     *
     * Establishes that the supplied buffer was filled completely or that a clean stream end was observed before any
     * byte of the next frame. [BufferRead.Rejected] closes partial and I/O failure. Raw bytes remain inside
     * [IndexerWireFrameCodec].
     */
    private fun readCompletely(
        channel: SocketChannel,
        buffer: ByteBuffer,
        deadline: IndexerFrameDeadline,
        input: IndexerConnectionInput,
    ): BufferRead =
        try {
            withNonBlockingChannel(channel) {
                Selector.open().use { selector ->
                    channel.register(selector, SelectionKey.OP_READ)
                    while (buffer.hasRemaining()) {
                        if (Thread.currentThread().isInterrupted) return BufferRead.Rejected
                        if (deadline.remainingMillis() <= 0) return BufferRead.TimedOut
                        when (input.read(buffer)) {
                            -1 -> return if (buffer.position() == 0) BufferRead.EndOfStream else BufferRead.Rejected
                            0 -> {
                                selector.select(deadline.remainingMillis().coerceAtLeast(1))
                                selector.selectedKeys().clear()
                            }
                        }
                    }
                }
            }
            BufferRead.Complete
        } catch (_: IOException) {
            BufferRead.Rejected
        } catch (_: CancelledKeyException) {
            BufferRead.Rejected
        }
}

private sealed interface BufferRead {
    data object Complete : BufferRead

    data object EndOfStream : BufferRead

    data object Rejected : BufferRead

    data object TimedOut : BufferRead
}

/** One elapsed budget is retained across header and payload; partial progress cannot renew it. */
private class IndexerFrameDeadline(private val limit: ElapsedTimeLimitMillis) {
    private val started = System.nanoTime()

    fun remainingMillis(): Long = limit.value - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
}

private inline fun <Value> withNonBlockingChannel(channel: SocketChannel, operation: () -> Value): Value {
    val wasBlocking = channel.isBlocking
    channel.configureBlocking(false)
    try {
        return operation()
    } finally {
        if (wasBlocking && channel.isOpen) {
            try {
                channel.configureBlocking(true)
            } catch (_: IOException) {}
        }
    }
}
