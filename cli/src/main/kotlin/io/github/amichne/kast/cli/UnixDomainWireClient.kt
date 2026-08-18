package io.github.amichne.kast.cli

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

private const val MAX_WIRE_FRAME_BYTES = 8 * 1_024 * 1_024

fun interface WireClient {
    /**
     * Proof transition: `RuntimeEndpoint + String -> WireExchange`.
     *
     * Establishes one bounded request/response exchange through the exact-root endpoint.
     * [WireTransportFailure] is the closed expected failure. Raw documents may be extracted only
     * by the canonical wire codec and typed projection.
     */
    fun exchange(
        endpoint: RuntimeEndpoint,
        document: String,
    ): WireExchange
}

sealed interface WireExchange {
    data class Received(
        val document: String,
    ) : WireExchange

    data class Rejected(
        val failure: WireTransportFailure,
    ) : WireExchange
}

enum class WireTransportFailure {
    REQUEST_TOO_LARGE,
    CONNECTION_FAILED,
    WRITE_FAILED,
    READ_FAILED,
    INVALID_FRAME_LENGTH,
    TRUNCATED_FRAME,
}

/** JDK-native Unix-domain-socket client using one bounded length-prefixed UTF-8 frame. */
class UnixDomainWireClient : WireClient {
    override fun exchange(
        endpoint: RuntimeEndpoint,
        document: String,
    ): WireExchange {
        val channel = try {
            SocketChannel.open(StandardProtocolFamily.UNIX)
        } catch (_: IOException) {
            return WireExchange.Rejected(WireTransportFailure.CONNECTION_FAILED)
        } catch (_: UnsupportedOperationException) {
            return WireExchange.Rejected(WireTransportFailure.CONNECTION_FAILED)
        }
        return channel.use { socket ->
            try {
                socket.connect(UnixDomainSocketAddress.of(endpoint.socketPath))
            } catch (_: IOException) {
                return@use WireExchange.Rejected(WireTransportFailure.CONNECTION_FAILED)
            } catch (_: SecurityException) {
                return@use WireExchange.Rejected(WireTransportFailure.CONNECTION_FAILED)
            }
            when (val written = WireFrameCodec.write(socket, document)) {
                WireFrameWrite.Written -> when (val read = WireFrameCodec.read(socket)) {
                    is WireFrameRead.Received -> WireExchange.Received(read.document)
                    is WireFrameRead.Rejected -> WireExchange.Rejected(read.failure)
                }
                is WireFrameWrite.Rejected -> WireExchange.Rejected(written.failure)
            }
        }
    }
}

sealed interface WireFrameWrite {
    data object Written : WireFrameWrite

    data class Rejected(
        val failure: WireTransportFailure,
    ) : WireFrameWrite
}

sealed interface WireFrameRead {
    data class Received(
        val document: String,
    ) : WireFrameRead

    data class Rejected(
        val failure: WireTransportFailure,
    ) : WireFrameRead
}

/** Length-prefixed framing isolated from semantic wire interpretation. */
internal object WireFrameCodec {
    /**
     * Proof transition: `SocketChannel + String -> WireFrameWrite`.
     *
     * Establishes that one bounded UTF-8 frame was completely written.
     * [WireTransportFailure] is the closed expected failure. Raw bytes remain inside this adapter.
     */
    fun write(
        channel: SocketChannel,
        document: String,
    ): WireFrameWrite {
        val payload = document.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_WIRE_FRAME_BYTES) {
            return WireFrameWrite.Rejected(WireTransportFailure.REQUEST_TOO_LARGE)
        }
        val frame = ByteBuffer.allocate(Int.SIZE_BYTES + payload.size)
            .putInt(payload.size)
            .put(payload)
            .flip()
        return try {
            while (frame.hasRemaining()) channel.write(frame)
            WireFrameWrite.Written
        } catch (_: IOException) {
            WireFrameWrite.Rejected(WireTransportFailure.WRITE_FAILED)
        }
    }

    /**
     * Proof transition: `SocketChannel -> WireFrameRead`.
     *
     * Establishes one complete, bounded, length-prefixed UTF-8 response document.
     * [WireTransportFailure] is the closed expected failure. Raw bytes remain inside this adapter.
     */
    fun read(channel: SocketChannel): WireFrameRead {
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
        when (readCompletely(channel, header)) {
            BufferRead.COMPLETE -> Unit
            BufferRead.TRUNCATED -> return WireFrameRead.Rejected(
                WireTransportFailure.TRUNCATED_FRAME,
            )
            BufferRead.FAILED -> return WireFrameRead.Rejected(WireTransportFailure.READ_FAILED)
        }
        header.flip()
        val length = header.int
        if (length < 0 || length > MAX_WIRE_FRAME_BYTES) {
            return WireFrameRead.Rejected(WireTransportFailure.INVALID_FRAME_LENGTH)
        }
        val payload = ByteBuffer.allocate(length)
        when (readCompletely(channel, payload)) {
            BufferRead.COMPLETE -> Unit
            BufferRead.TRUNCATED -> return WireFrameRead.Rejected(
                WireTransportFailure.TRUNCATED_FRAME,
            )
            BufferRead.FAILED -> return WireFrameRead.Rejected(WireTransportFailure.READ_FAILED)
        }
        payload.flip()
        return WireFrameRead.Received(StandardCharsets.UTF_8.decode(payload).toString())
    }

    private fun readCompletely(
        channel: SocketChannel,
        buffer: ByteBuffer,
    ): BufferRead = try {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) return BufferRead.TRUNCATED
        }
        BufferRead.COMPLETE
    } catch (_: IOException) {
        BufferRead.FAILED
    }
}

private enum class BufferRead {
    COMPLETE,
    TRUNCATED,
    FAILED,
}
