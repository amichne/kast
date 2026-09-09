package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.WireRuntimeIdentity
import io.github.amichne.kast.distribution.contract.WireRuntimeQualification
import io.github.amichne.kast.distribution.contract.WirePeerQualification
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

private const val MAX_WIRE_FRAME_BYTES = io.github.amichne.kast.distribution.contract.IndexerTransportLimits.maximumFrameBytes

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

    /** Installed dispatch must qualify its exact process on the same owned connection. */
    fun exchange(endpoint: RuntimeEndpoint, document: String,
        identity: io.github.amichne.kast.distribution.contract.WireRuntimeIdentity,
        budget: io.github.amichne.kast.kernel.ElapsedTimeLimitMillis): WireExchange =
        WireExchange.Rejected(WireTransportFailure.UNQUALIFIED_PEER)
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
    UNQUALIFIED_PEER,
    TIMED_OUT,
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
    private val budget: io.github.amichne.kast.kernel.ElapsedTimeLimitMillis =
        io.github.amichne.kast.protocol.registry.OperationExecutionBudget.GRAPH_BUILD.invocation,
    private val activity: WireActivitySink = WireActivitySink.Disabled,
) : AutoCloseable {
    private sealed interface Authority {
        data object Unqualified : Authority
        class Qualified(val identity: WireRuntimeIdentity) : Authority
        data object Rejected : Authority
    }
    private var authority: Authority = Authority.Unqualified

    fun exchange(document: String, identity: WireRuntimeIdentity): WireExchange = boundedExchange {
        when (val retained = authority) {
            is Authority.Qualified -> if (retained.identity.sameProcess(identity)) transfer(document)
                else WireExchange.Rejected(WireTransportFailure.UNQUALIFIED_PEER)
            Authority.Rejected -> WireExchange.Rejected(WireTransportFailure.UNQUALIFIED_PEER)
            Authority.Unqualified -> qualifyAndTransfer(document, identity)
        }
    }

    private fun qualifyAndTransfer(document: String, identity: WireRuntimeIdentity): WireExchange =
        when (val qualification = transfer(WireRuntimeQualification.request(identity))) {
            is WireExchange.Rejected -> qualification
            is WireExchange.Received -> when (WireRuntimeQualification.admitResponse(qualification.document, identity)) {
                WirePeerQualification.REJECTED -> {
                    activity.publish(WireRequestActivity(WireRequestStage.PEER_QUALIFICATION, WireRequestOutcome.REJECTED))
                    WireExchange.Rejected(WireTransportFailure.UNQUALIFIED_PEER)
                }
                WirePeerQualification.QUALIFIED -> {
                    authority = Authority.Qualified(identity)
                    activity.publish(WireRequestActivity(WireRequestStage.PEER_QUALIFICATION, WireRequestOutcome.COMPLETED))
                    transfer(document)
                }
            }
        }
    /**
     * Proof transition: `connected WireSession + String -> WireExchange`.
     *
     * Establishes one bounded request/response exchange on the already-admitted endpoint.
     * [WireTransportFailure] is the closed expected failure. Raw documents remain confined to the
     * framing and canonical wire boundaries.
     */
    fun exchange(document: String): WireExchange = boundedExchange { transfer(document) }

    private fun transfer(document: String): WireExchange = when (val written = WireFrameCodec.write(channel, document)) {
        WireFrameWrite.Written -> when (val read = WireFrameCodec.read(channel)) {
            is WireFrameRead.Received -> WireExchange.Received(read.document)
            is WireFrameRead.Rejected -> WireExchange.Rejected(read.failure)
        }
        is WireFrameWrite.Rejected -> WireExchange.Rejected(written.failure)
    }

    private fun boundedExchange(operation: () -> WireExchange): WireExchange {
        if (authority == Authority.Rejected) return WireExchange.Rejected(WireTransportFailure.UNQUALIFIED_PEER)
        val deadline = WireIoDeadline(channel, budget)
        val result = try { operation() } finally { deadline.finish() }
        val outcome = deadline.finish()
        activity.publish(WireRequestActivity(WireRequestStage.EXCHANGE,
            if (outcome == WireRequestOutcome.TIMED_OUT) outcome
            else if (result is WireExchange.Received) WireRequestOutcome.COMPLETED else WireRequestOutcome.REJECTED))
        val observed = if (outcome == WireRequestOutcome.TIMED_OUT) WireExchange.Rejected(WireTransportFailure.TIMED_OUT) else result
        if (observed is WireExchange.Rejected) {
            authority = Authority.Rejected
            close()
        }
        return observed
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
class UnixDomainWireClient(
    private val budget: io.github.amichne.kast.kernel.ElapsedTimeLimitMillis =
        io.github.amichne.kast.protocol.registry.OperationExecutionBudget.GRAPH_BUILD.invocation,
    private val activity: WireActivitySink = WireActivitySink.Disabled,
) : WireClient {
    override fun exchange(
        endpoint: RuntimeEndpoint,
        document: String,
    ): WireExchange = when (val opening = open(endpoint)) {
        is WireSessionOpening.Opened -> opening.session.use { it.exchange(document) }
        is WireSessionOpening.Rejected -> WireExchange.Rejected(opening.failure)
    }

    override fun exchange(endpoint: RuntimeEndpoint, document: String, identity: WireRuntimeIdentity,
        budget: ElapsedTimeLimitMillis): WireExchange {
        if (identity.root != endpoint.root.path || identity.runtimeId != endpoint.runtimeId.value)
            return WireExchange.Rejected(WireTransportFailure.UNQUALIFIED_PEER)
        return when (val opening = open(endpoint, budget)) {
            is WireSessionOpening.Opened -> opening.session.use { it.exchange(document, identity) }
            is WireSessionOpening.Rejected -> WireExchange.Rejected(opening.failure)
        }
    }

    /**
     * Proof transition: `RuntimeEndpoint -> WireSessionOpening`.
     *
     * Establishes one connected session bound to the exact endpoint. The closed expected failure is
     * [WireSessionOpening.Rejected]. Raw socket paths leave only at the JDK connection boundary.
     */
    fun open(endpoint: RuntimeEndpoint, allowance: ElapsedTimeLimitMillis = budget): WireSessionOpening {
        val admittedEndpoint = when (val admission = endpoint.observeTransport()) {
            is RuntimeEndpointResolution.Resolved -> admission.endpoint
            is RuntimeEndpointResolution.Rejected -> return WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
        }
        val channel = try {
            SocketChannel.open(StandardProtocolFamily.UNIX)
        } catch (_: IOException) {
            return WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
        } catch (_: UnsupportedOperationException) {
            return WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
        }
        val deadline = WireIoDeadline(channel, allowance)
        val started = System.nanoTime()
        val result = try {
            channel.connect(UnixDomainSocketAddress.of(admittedEndpoint.socketPath))
            if (admittedEndpoint.observeTransport() is RuntimeEndpointResolution.Rejected) {
                deadline.finish()
                channel.closeQuietly()
                return WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
            }
            val remaining = allowance.value - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            when (val admitted = io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(remaining)) {
                is io.github.amichne.kast.kernel.Refinement.Refined ->
                    WireSessionOpening.Opened(WireSession(channel, admitted.value, activity))
                is io.github.amichne.kast.kernel.Refinement.Rejected -> {
                    channel.closeQuietly()
                    WireSessionOpening.Rejected(WireTransportFailure.TIMED_OUT)
                }
            }
        } catch (_: IOException) {
            channel.closeQuietly()
            WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
        } catch (_: SecurityException) {
            channel.closeQuietly()
            WireSessionOpening.Rejected(WireTransportFailure.CONNECTION_FAILED)
        }
        val outcome = deadline.finish()
        activity.publish(WireRequestActivity(WireRequestStage.CONNECTION,
            if (outcome == WireRequestOutcome.TIMED_OUT) outcome
            else if (result is WireSessionOpening.Opened) WireRequestOutcome.COMPLETED else WireRequestOutcome.REJECTED))
        return if (outcome == WireRequestOutcome.TIMED_OUT) WireSessionOpening.Rejected(WireTransportFailure.TIMED_OUT) else result
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
