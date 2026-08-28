package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.runtime.ide.host.HostedIdeRuntime
import io.github.amichne.kast.runtime.ide.host.HostedIdeRuntimeDispatch
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

private const val MAX_IDE_ENDPOINT_FRAME_BYTES = 8 * 1_024 * 1_024

internal enum class IdeEndpointConnectionFailure {
    ACCEPT_FAILED,
    INVALID_REQUEST_FRAME,
    DISPATCH_REJECTED,
    RESPONSE_WRITE_FAILED,
    SESSION_CLOSE_FAILED,
}

internal sealed interface IdeEndpointConnectionHandling {
    data object Served : IdeEndpointConnectionHandling

    data class Rejected(
        val failure: IdeEndpointConnectionFailure,
    ) : IdeEndpointConnectionHandling
}

internal sealed interface IdeEndpointSocketAcceptance {
    data class Accepted(
        val connection: SocketChannel,
    ) : IdeEndpointSocketAcceptance

    data object Rejected : IdeEndpointSocketAcceptance
}

private sealed interface IdeEndpointFrameRead {
    data class Received(
        val document: String,
    ) : IdeEndpointFrameRead

    data object EndOfStream : IdeEndpointFrameRead
    data object Rejected : IdeEndpointFrameRead
}

private sealed interface IdeEndpointFrameWrite {
    data object Written : IdeEndpointFrameWrite
    data object Rejected : IdeEndpointFrameWrite
}

private enum class IdeEndpointBufferRead {
    COMPLETE,
    END_OF_STREAM,
    TRUNCATED,
    FAILED,
}

private enum class IdeEndpointBufferProgress {
    EMPTY,
    OBSERVED,
}

private enum class IdeEndpointSocketClose {
    CLOSED,
    FAILED,
}

private sealed interface IdeEndpointTransportState {
    data object Listening : IdeEndpointTransportState
    data class Serving(val connection: SocketChannel) : IdeEndpointTransportState
    data object Closed : IdeEndpointTransportState
}

private sealed interface IdeEndpointConnectionAdmission {
    data object Admitted : IdeEndpointConnectionAdmission
    data object Closed : IdeEndpointConnectionAdmission
}

/** Bound exact-root transport that can serve only its retained complete hosted runtime. */
internal class IdeEndpointTransport(
    private val socket: OwnedEndpointPath,
    private val runtime: HostedIdeRuntime,
) {
    private var state: IdeEndpointTransportState = IdeEndpointTransportState.Listening

    /**
     * Proof transition: `one accepted UDS session -> IdeEndpointConnectionHandling`.
     *
     * Establishes zero or more bounded length-prefixed request/response exchanges through the
     * complete runtime retained before publication. Framing, dispatch, and write failures remain
     * [IdeEndpointConnectionFailure]. Blocking socket I/O is invoked only by the project service's
     * IO coroutine; raw bytes and documents leave only at this transport boundary.
     */
    suspend fun serveNext(): IdeEndpointConnectionHandling {
        val connection = when (val accepted = socket.accept()) {
            is IdeEndpointSocketAcceptance.Accepted -> accepted.connection
            IdeEndpointSocketAcceptance.Rejected -> return IdeEndpointConnectionHandling.Rejected(
                IdeEndpointConnectionFailure.ACCEPT_FAILED,
            )
        }
        if (admit(connection) == IdeEndpointConnectionAdmission.Closed) {
            connection.closeQuietly()
            return IdeEndpointConnectionHandling.Rejected(
                IdeEndpointConnectionFailure.ACCEPT_FAILED,
            )
        }
        return try {
            val handling = serveFrames(connection)
            when (connection.closeObserved()) {
                IdeEndpointSocketClose.CLOSED -> handling
                IdeEndpointSocketClose.FAILED -> IdeEndpointConnectionHandling.Rejected(
                    IdeEndpointConnectionFailure.SESSION_CLOSE_FAILED,
                )
            }
        } finally {
            release(connection)
            connection.closeQuietly()
        }
    }

    /** Closes both the listener and the one sequential accepted client retained by this transport. */
    @Synchronized
    fun close() {
        val current = state
        state = IdeEndpointTransportState.Closed
        socket.close()
        if (current is IdeEndpointTransportState.Serving) current.connection.closeQuietly()
    }

    @Synchronized
    private fun admit(connection: SocketChannel): IdeEndpointConnectionAdmission = when (state) {
        IdeEndpointTransportState.Listening -> {
            state = IdeEndpointTransportState.Serving(connection)
            IdeEndpointConnectionAdmission.Admitted
        }
        IdeEndpointTransportState.Closed,
        is IdeEndpointTransportState.Serving,
        -> IdeEndpointConnectionAdmission.Closed
    }

    @Synchronized
    private fun release(connection: SocketChannel) {
        val current = state
        if (current is IdeEndpointTransportState.Serving && current.connection === connection) {
            state = IdeEndpointTransportState.Listening
        }
    }

    private suspend fun serveFrames(connection: SocketChannel): IdeEndpointConnectionHandling {
        while (true) {
            val request = when (val frame = IdeEndpointFrameCodec.read(connection)) {
                is IdeEndpointFrameRead.Received -> frame.document
                IdeEndpointFrameRead.EndOfStream -> return IdeEndpointConnectionHandling.Served
                IdeEndpointFrameRead.Rejected -> return IdeEndpointConnectionHandling.Rejected(
                    IdeEndpointConnectionFailure.INVALID_REQUEST_FRAME,
                )
            }
            when (val dispatch = runtime.dispatch(request)) {
                is HostedIdeRuntimeDispatch.Responded -> when (
                    IdeEndpointFrameCodec.write(connection, dispatch.document)
                ) {
                    IdeEndpointFrameWrite.Written -> Unit
                    IdeEndpointFrameWrite.Rejected -> return IdeEndpointConnectionHandling.Rejected(
                        IdeEndpointConnectionFailure.RESPONSE_WRITE_FAILED,
                    )
                }
                HostedIdeRuntimeDispatch.Rejected ->
                    return IdeEndpointConnectionHandling.Rejected(
                        IdeEndpointConnectionFailure.DISPATCH_REJECTED,
                    )
            }
        }
    }
}

private fun SocketChannel.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}

private fun SocketChannel.closeObserved(): IdeEndpointSocketClose = try {
    close()
    IdeEndpointSocketClose.CLOSED
} catch (_: IOException) {
    IdeEndpointSocketClose.FAILED
}

/** Bounded length-prefixed UTF-8 framing compatible with the public CLI wire client. */
private object IdeEndpointFrameCodec {
    /**
     * Proof transition: `SocketChannel -> IdeEndpointFrameRead`.
     *
     * Establishes one complete bounded length-prefixed UTF-8 document, clean end-of-stream, or
     * closed malformed/I/O rejection. Raw bytes and channel reads leave only at this frame codec.
     */
    fun read(channel: SocketChannel): IdeEndpointFrameRead {
        val header = ByteBuffer.allocate(Int.SIZE_BYTES)
        when (readCompletely(channel, header)) {
            IdeEndpointBufferRead.COMPLETE -> Unit
            IdeEndpointBufferRead.END_OF_STREAM -> return IdeEndpointFrameRead.EndOfStream
            IdeEndpointBufferRead.TRUNCATED,
            IdeEndpointBufferRead.FAILED,
            -> return IdeEndpointFrameRead.Rejected
        }
        header.flip()
        val length = header.int
        if (length !in 0..MAX_IDE_ENDPOINT_FRAME_BYTES) return IdeEndpointFrameRead.Rejected
        val payload = ByteBuffer.allocate(length)
        when (readCompletely(channel, payload)) {
            IdeEndpointBufferRead.COMPLETE -> Unit
            IdeEndpointBufferRead.END_OF_STREAM,
            IdeEndpointBufferRead.TRUNCATED,
            IdeEndpointBufferRead.FAILED,
            -> return IdeEndpointFrameRead.Rejected
        }
        payload.flip()
        return IdeEndpointFrameRead.Received(StandardCharsets.UTF_8.decode(payload).toString())
    }

    /**
     * Proof transition: `(SocketChannel, canonical response String) -> IdeEndpointFrameWrite`.
     *
     * Establishes a complete bounded length-prefixed UTF-8 write or the closed
     * [IdeEndpointFrameWrite.Rejected] state. Raw documents and bytes leave only at this codec.
     */
    fun write(
        channel: SocketChannel,
        document: String,
    ): IdeEndpointFrameWrite {
        val payload = document.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_IDE_ENDPOINT_FRAME_BYTES) return IdeEndpointFrameWrite.Rejected
        val frame = ByteBuffer.allocate(Int.SIZE_BYTES + payload.size)
            .putInt(payload.size)
            .put(payload)
            .flip()
        return try {
            while (frame.hasRemaining()) channel.write(frame)
            IdeEndpointFrameWrite.Written
        } catch (_: IOException) {
            IdeEndpointFrameWrite.Rejected
        }
    }

    private fun readCompletely(
        channel: SocketChannel,
        buffer: ByteBuffer,
    ): IdeEndpointBufferRead = try {
        var progress = IdeEndpointBufferProgress.EMPTY
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return when (progress) {
                    IdeEndpointBufferProgress.EMPTY -> IdeEndpointBufferRead.END_OF_STREAM
                    IdeEndpointBufferProgress.OBSERVED -> IdeEndpointBufferRead.TRUNCATED
                }
            }
            progress = IdeEndpointBufferProgress.OBSERVED
        }
        IdeEndpointBufferRead.COMPLETE
    } catch (_: IOException) {
        IdeEndpointBufferRead.FAILED
    }
}
