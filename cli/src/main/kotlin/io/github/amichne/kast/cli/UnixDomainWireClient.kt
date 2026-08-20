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

/** One connected exact-root wire session that can exchange multiple canonical frames. */
class WireSession internal constructor(
    private val channel: SocketChannel,
) : AutoCloseable {
    /**
     * Proof transition: `connected WireSession + String -> WireExchange`.
     *
     * Establishes one bounded request/response exchange on the already-admitted endpoint.
     * [WireTransportFailure] is the closed expected failure. Raw documents remain confined to the
     * framing and canonical wire boundaries.
     */
    fun exchange(document: String): WireExchange =
        when (val written = WireFrameCodec.write(channel, document)) {
            WireFrameWrite.Written -> when (val read = WireFrameCodec.read(channel)) {
                is WireFrameRead.Received -> WireExchange.Received(read.document)
                is WireFrameRead.Rejected -> WireExchange.Rejected(read.failure)
            }
            is WireFrameWrite.Rejected -> WireExchange.Rejected(written.failure)
        }

    override fun close() {
        try {
            channel.close()
        } catch (_: IOException) {
        }
    }
}

sealed interface WireSessionOpening {
    data class Opened(
        val session: WireSession,
    ) : WireSessionOpening

    data class Rejected(
        val failure: WireTransportFailure,
    ) : WireSessionOpening
}

/** JDK-native Unix-domain client with reusable bounded length-prefixed UTF-8 sessions. */
class UnixDomainWireClient : WireClient {
    override fun exchange(
        endpoint: RuntimeEndpoint,
        document: String,
    ): WireExchange = when (val opening = open(endpoint)) {
        is WireSessionOpening.Opened -> opening.session.use { it.exchange(document) }
        is WireSessionOpening.Rejected -> WireExchange.Rejected(opening.failure)
    }

    /**
     * Proof transition: `RuntimeEndpoint -> WireSessionOpening`.
     *
     * Establishes one connected session bound to the exact endpoint. The closed expected failure is
     * [WireSessionOpening.Rejected]. Raw socket paths leave only at the JDK connection boundary.
     */
    fun open(endpoint: RuntimeEndpoint): WireSessionOpening {
        val channel = try {
            SocketChannel.open(StandardProtocolFamily.UNIX)
        } catch (_: IOException) {
            return WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
        } catch (_: UnsupportedOperationException) {
            return WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
        }
        return try {
            channel.connect(UnixDomainSocketAddress.of(endpoint.socketPath))
            WireSessionOpening.Opened(WireSession(channel))
        } catch (_: IOException) {
            channel.closeQuietly()
            WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
        } catch (_: SecurityException) {
            channel.closeQuietly()
            WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
        }
    }
}

private fun SocketChannel.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
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
