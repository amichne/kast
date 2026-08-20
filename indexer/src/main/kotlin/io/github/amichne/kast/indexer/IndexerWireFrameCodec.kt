package io.github.amichne.kast.indexer

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

private const val MAX_INDEXER_FRAME_BYTES = 8 * 1_024 * 1_024

sealed interface IndexerFrameRead {
    data class Received(
        val document: String,
    ) : IndexerFrameRead

    data object EndOfStream : IndexerFrameRead

    data object Rejected : IndexerFrameRead
}

sealed interface IndexerFrameWrite {
    data object Written : IndexerFrameWrite

    data object Rejected : IndexerFrameWrite
}

/** Bounded length-prefixed UTF-8 framing at the installed host boundary. */
internal object IndexerWireFrameCodec {
    /**
     * Proof transition: `SocketChannel -> IndexerFrameRead`.
     *
     * Establishes one complete frame of at most eight MiB or a clean connection-end state before
     * another frame begins. Rejection is closed by [IndexerFrameRead.Rejected]. Raw bytes leave
     * only as the received boundary document.
     */
    fun read(channel: SocketChannel): IndexerFrameRead {
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
        when (readCompletely(channel, header)) {
            BufferRead.Complete -> Unit
            BufferRead.EndOfStream -> return IndexerFrameRead.EndOfStream
            BufferRead.Rejected -> return IndexerFrameRead.Rejected
        }
        header.flip()
        val length = header.int
        if (length !in 0..MAX_INDEXER_FRAME_BYTES) return IndexerFrameRead.Rejected
        val payload = ByteBuffer.allocate(length)
        when (readCompletely(channel, payload)) {
            BufferRead.Complete -> Unit
            BufferRead.EndOfStream,
            BufferRead.Rejected,
                -> return IndexerFrameRead.Rejected
        }
        payload.flip()
        return IndexerFrameRead.Received(StandardCharsets.UTF_8.decode(payload).toString())
    }

    /**
     * Proof transition: `SocketChannel + String -> IndexerFrameWrite`.
     *
     * Establishes that one response of at most eight MiB was completely written. Rejection is
     * closed by [IndexerFrameWrite.Rejected]. Raw bytes remain inside this transport adapter.
     */
    fun write(
        channel: SocketChannel,
        document: String,
    ): IndexerFrameWrite {
        val payload = document.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_INDEXER_FRAME_BYTES) return IndexerFrameWrite.Rejected
        val frame = ByteBuffer.allocate(Int.SIZE_BYTES + payload.size)
            .putInt(payload.size)
            .put(payload)
            .flip()
        return try {
            while (frame.hasRemaining()) channel.write(frame)
            IndexerFrameWrite.Written
        } catch (_: IOException) {
            IndexerFrameWrite.Rejected
        }
    }

    /**
     * Proof transition: `SocketChannel + ByteBuffer -> BufferRead`.
     *
     * Establishes that the supplied buffer was filled completely or that a clean stream end was
     * observed before any byte of the next frame. [BufferRead.Rejected] closes partial and I/O
     * failure. Raw bytes remain inside [IndexerWireFrameCodec].
     */
    private fun readCompletely(
        channel: SocketChannel,
        buffer: ByteBuffer,
    ): BufferRead = try {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return if (buffer.position() == 0) {
                    BufferRead.EndOfStream
                } else {
                    BufferRead.Rejected
                }
            }
        }
        BufferRead.Complete
    } catch (_: IOException) {
        BufferRead.Rejected
    }
}

private sealed interface BufferRead {
    data object Complete : BufferRead
    data object EndOfStream : BufferRead
    data object Rejected : BufferRead
}
