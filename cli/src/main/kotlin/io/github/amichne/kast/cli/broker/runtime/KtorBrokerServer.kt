package io.github.amichne.kast.cli.broker.runtime

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.protocol.ThreadCatalogStore
import io.github.amichne.kast.cli.broker.protocol.codex.CodexProtocolAdapter
import io.github.amichne.kast.cli.broker.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.cli.broker.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.Validation
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger

internal enum class BrokerSocketPathFailure {
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    TOO_LONG,
}

@JvmInline
internal value class BrokerSocketPath private constructor(
    val path: Path,
) {
    companion object {
        internal fun admit(candidate: Path): Validation<BrokerSocketPath, BrokerSocketPathFailure> =
            when {
                !candidate.isAbsolute -> Validation.rejected(BrokerSocketPathFailure.NOT_ABSOLUTE)
                candidate.normalize() != candidate ->
                    Validation.rejected(BrokerSocketPathFailure.NOT_NORMALIZED)
                candidate.toString().toByteArray(StandardCharsets.UTF_8).size >= UNIX_PATH_BYTES ->
                    Validation.rejected(BrokerSocketPathFailure.TOO_LONG)
                else -> Validation.validated(BrokerSocketPath(candidate))
            }

        private const val UNIX_PATH_BYTES = 104
    }
}

internal sealed interface BrokerUpstreamFrame {
    data class Text(val message: String) : BrokerUpstreamFrame
    data object Closed : BrokerUpstreamFrame
    data object Rejected : BrokerUpstreamFrame
}

internal enum class BrokerUpstreamSend { SENT, REJECTED }

internal interface BrokerUpstreamConnection {
    suspend fun send(message: String): BrokerUpstreamSend
    suspend fun receive(): BrokerUpstreamFrame
    suspend fun close()
}

internal sealed interface BrokerUpstreamConnectionAdmission {
    data class Connected(val connection: BrokerUpstreamConnection) : BrokerUpstreamConnectionAdmission
    data object Rejected : BrokerUpstreamConnectionAdmission
}

internal fun interface BrokerUpstreamConnector {
    suspend fun connect(): BrokerUpstreamConnectionAdmission
}

internal data class KtorBrokerServerOptions(
    val publicSocket: BrokerSocketPath,
    val broker: Broker,
    val contracts: CodexProtocolContracts,
    val threadStore: ThreadCatalogStore,
    val upstream: BrokerUpstreamConnector,
    val maximumConnections: Int,
    val maximumMessageBytes: Int,
    val connectionInitializationTimeoutMillis: Long = 10_000,
)

internal enum class KtorBrokerServerFailure {
    INVALID_LIMIT,
    SOCKET_PARENT_REJECTED,
    SOCKET_PATH_OWNED,
    SOCKET_PATH_REJECTED,
    SERVER_START_REJECTED,
    SOCKET_IDENTITY_REJECTED,
}

internal sealed interface KtorBrokerServerStart {
    data class Started(val server: KtorBrokerServer) : KtorBrokerServerStart
    data class Rejected(val failure: KtorBrokerServerFailure) : KtorBrokerServerStart
}

internal class KtorBrokerServer private constructor(
    private val engine: EmbeddedServer<*, *>,
    private val ownedSocket: OwnedUnixSocket,
) {
    internal suspend fun close() {
        try {
            engine.stopSuspend(gracePeriodMillis = 500, timeoutMillis = 2_000)
        } finally {
            ownedSocket.retire()
        }
    }

    companion object {
        internal suspend fun start(options: KtorBrokerServerOptions): KtorBrokerServerStart {
            if (
                options.maximumConnections <= 0 || options.maximumMessageBytes <= 0 ||
                options.connectionInitializationTimeoutMillis <= 0
            ) {
                return KtorBrokerServerStart.Rejected(KtorBrokerServerFailure.INVALID_LIMIT)
            }
            when (UnixSocketPathOwnership.prepare(options.publicSocket.path)) {
                UnixSocketPathPreparation.PREPARED -> Unit
                UnixSocketPathPreparation.OWNED -> return KtorBrokerServerStart.Rejected(
                    KtorBrokerServerFailure.SOCKET_PATH_OWNED,
                )
                UnixSocketPathPreparation.REJECTED -> return KtorBrokerServerStart.Rejected(
                    KtorBrokerServerFailure.SOCKET_PATH_REJECTED,
                )
                UnixSocketPathPreparation.PARENT_REJECTED -> return KtorBrokerServerStart.Rejected(
                    KtorBrokerServerFailure.SOCKET_PARENT_REJECTED,
                )
            }
            val connectionCount = AtomicInteger(0)
            val engine = embeddedServer(
                factory = CIO,
                configure = { unixConnector(options.publicSocket.path.toString()) },
                module = {
                    install(WebSockets) {
                        maxFrameSize = options.maximumMessageBytes.toLong()
                    }
                    routing {
                        webSocket("/") {
                            val count = connectionCount.incrementAndGet()
                            try {
                                if (count > options.maximumConnections) {
                                    close(
                                        CloseReason(
                                            CloseReason.Codes.TRY_AGAIN_LATER,
                                            "connection limit exceeded",
                                        ),
                                    )
                                } else {
                                    bridgeConnection(this, options)
                                }
                            } finally {
                                connectionCount.decrementAndGet()
                            }
                        }
                    }
                },
            )
            try {
                engine.startSuspend(wait = false)
                Files.setPosixFilePermissions(
                    options.publicSocket.path,
                    PosixFilePermissions.fromString("rw-------"),
                )
            } catch (_: Exception) {
                engine.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
                return KtorBrokerServerStart.Rejected(
                    KtorBrokerServerFailure.SERVER_START_REJECTED,
                )
            }
            val owned = OwnedUnixSocket.capture(options.publicSocket.path)
                ?: run {
                    engine.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
                    return KtorBrokerServerStart.Rejected(
                        KtorBrokerServerFailure.SOCKET_IDENTITY_REJECTED,
                    )
                }
            return KtorBrokerServerStart.Started(KtorBrokerServer(engine, owned))
        }

        private suspend fun bridgeConnection(
            downstream: DefaultWebSocketServerSession,
            options: KtorBrokerServerOptions,
        ) {
            val initialize = receiveInitialize(downstream, options) ?: return
            val upstream = when (val admission = options.upstream.connect()) {
                is BrokerUpstreamConnectionAdmission.Connected -> admission.connection
                BrokerUpstreamConnectionAdmission.Rejected -> {
                    downstream.close(
                        CloseReason(CloseReason.Codes.INTERNAL_ERROR, "upstream unavailable"),
                    )
                    return
                }
            }
            val adapter = CodexProtocolAdapter(options.broker, options.contracts, options.threadStore)
            try {
                val initializationRouting = adapter.fromDownstream(initialize.message)
                if (initializationRouting !is ProtocolRouting.ForwardUpstream) {
                    applyRouting(initializationRouting, downstream, upstream)
                    closeBoth(downstream, upstream, "initialize rejected")
                    return
                }
                if (upstream.send(initializationRouting.message) != BrokerUpstreamSend.SENT) {
                    closeBoth(downstream, upstream, "initialize unavailable")
                    return
                }
                val initialization = CompletableDeferred<Boolean>()
                coroutineScope {
                    val downstreamPump = launch {
                        try {
                            val initialized = withTimeoutOrNull(
                                options.connectionInitializationTimeoutMillis,
                            ) {
                                initialization.await()
                            } == true
                            if (!initialized) {
                                closeBoth(downstream, upstream, "initialize failed")
                                return@launch
                            }
                            for (frame in downstream.incoming) {
                                val text = (frame as? Frame.Text)?.readText()
                                if (text == null || text.utf8Bytes() > options.maximumMessageBytes) {
                                    closeBoth(downstream, upstream, "unsupported downstream frame")
                                    break
                                }
                                if (!applyRouting(adapter.fromDownstream(text), downstream, upstream)) break
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            closeBoth(downstream, upstream, "downstream bridge failed")
                        }
                    }
                    val upstreamPump = launch {
                        try {
                            while (true) {
                                when (val frame = upstream.receive()) {
                                    is BrokerUpstreamFrame.Text -> {
                                        val initializationStatus = initializationResponse(
                                            frame.message,
                                            initialize.idKey,
                                        )
                                        when (initializationStatus) {
                                            InitializationResponse.SUCCESS -> initialization.complete(true)
                                            InitializationResponse.FAILURE -> initialization.complete(false)
                                            InitializationResponse.UNRELATED -> Unit
                                        }
                                        if (
                                            frame.message.utf8Bytes() > options.maximumMessageBytes ||
                                            !applyRouting(
                                                adapter.fromUpstream(frame.message),
                                                downstream,
                                                upstream,
                                            )
                                        ) break
                                        if (initializationStatus == InitializationResponse.FAILURE) break
                                    }
                                    BrokerUpstreamFrame.Closed,
                                    BrokerUpstreamFrame.Rejected,
                                        -> {
                                            initialization.complete(false)
                                            downstream.close(
                                                CloseReason(
                                                    CloseReason.Codes.INTERNAL_ERROR,
                                                    "upstream closed",
                                                ),
                                            )
                                            break
                                        }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            initialization.complete(false)
                            closeBoth(downstream, upstream, "upstream bridge failed")
                        }
                    }
                    select<Unit> {
                        downstreamPump.onJoin { }
                        upstreamPump.onJoin { }
                    }
                    downstreamPump.cancelAndJoin()
                    upstreamPump.cancelAndJoin()
                }
            } finally {
                adapter.close()
                upstream.close()
            }
        }

        private suspend fun receiveInitialize(
            downstream: DefaultWebSocketServerSession,
            options: KtorBrokerServerOptions,
        ): InitializeRequest? {
            val frame = withTimeoutOrNull(options.connectionInitializationTimeoutMillis) {
                downstream.incoming.receiveCatching().getOrNull()
            }
            val message = (frame as? Frame.Text)?.readText()
            val request = message?.takeIf { it.utf8Bytes() <= options.maximumMessageBytes }
                ?.let(::initializeRequest)
            if (request == null) {
                downstream.close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "initialize must be first"),
                )
            }
            return request
        }

        private fun initializeRequest(message: String): InitializeRequest? {
            val document = parseObject(message) ?: return null
            if (document.string("method") != "initialize") return null
            val idKey = rpcIdKey(document["id"]) ?: return null
            return InitializeRequest(message, idKey)
        }

        private fun initializationResponse(
            message: String,
            initializeIdKey: String,
        ): InitializationResponse {
            val document = parseObject(message) ?: return InitializationResponse.UNRELATED
            if (document.string("method") != null) return InitializationResponse.UNRELATED
            if (rpcIdKey(document["id"]) != initializeIdKey) {
                return InitializationResponse.UNRELATED
            }
            return when {
                document.containsKey("error") -> InitializationResponse.FAILURE
                document.containsKey("result") -> InitializationResponse.SUCCESS
                else -> InitializationResponse.UNRELATED
            }
        }

        private fun parseObject(message: String): JsonObject? = try {
            Json.parseToJsonElement(message) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun JsonObject.string(name: String): String? =
            (get(name) as? JsonPrimitive)?.contentOrNull

        private fun rpcIdKey(candidate: JsonElement?): String? {
            val primitive = candidate as? JsonPrimitive ?: return null
            if (primitive.isString) return "string:${primitive.content}"
            val numeric = primitive.content.toBigDecimalOrNull() ?: return null
            return "number:${numeric.toPlainString()}"
        }

        private suspend fun applyRouting(
            routing: ProtocolRouting,
            downstream: DefaultWebSocketServerSession,
            upstream: BrokerUpstreamConnection,
        ): Boolean = when (routing) {
            is ProtocolRouting.ForwardUpstream,
            is ProtocolRouting.ReplyUpstream,
                -> upstream.send(routing.message()) == BrokerUpstreamSend.SENT
            is ProtocolRouting.ForwardDownstream,
            is ProtocolRouting.ReplyDownstream,
                -> {
                    downstream.send(routing.message())
                    true
                }
            is ProtocolRouting.Close -> {
                closeBoth(downstream, upstream, "protocol rejected")
                false
            }
        }

        private fun ProtocolRouting.message(): String = when (this) {
            is ProtocolRouting.ForwardUpstream -> message
            is ProtocolRouting.ForwardDownstream -> message
            is ProtocolRouting.ReplyUpstream -> message
            is ProtocolRouting.ReplyDownstream -> message
            is ProtocolRouting.Close -> error("Closed routing has no message")
        }

        private suspend fun closeBoth(
            downstream: DefaultWebSocketServerSession,
            upstream: BrokerUpstreamConnection,
            reason: String,
        ) {
            upstream.close()
            downstream.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, reason))
        }

        private fun String.utf8Bytes(): Int = toByteArray(StandardCharsets.UTF_8).size
    }
}

private data class InitializeRequest(val message: String, val idKey: String)

private enum class InitializationResponse { UNRELATED, SUCCESS, FAILURE }
