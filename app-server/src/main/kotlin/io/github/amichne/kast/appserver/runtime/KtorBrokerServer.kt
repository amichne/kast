package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.core.AgentSessionBootstrap
import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerInvocationActivitySink
import io.github.amichne.kast.appserver.protocol.ThreadCatalogStore
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolAdapter
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
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
    ALIAS_REJECTED,
    UPSTREAM_DIRECTORY_REJECTED,
}

internal sealed interface BrokerSocketRoute {
    data object Canonical : BrokerSocketRoute
    data class Aliased(val receipt: io.github.amichne.kast.appserver.BrokerEndpointAliasReceipt) : BrokerSocketRoute
    data class PrivateUpstream(
        val receipt: io.github.amichne.kast.appserver.BrokerUpstreamDirectoryReceipt,
    ) : BrokerSocketRoute
}

internal class BrokerSocketPath private constructor(
    val path: Path,
    val physicalPath: Path,
    val route: BrokerSocketRoute,
) {
    internal fun revalidate(): Validation<BrokerSocketPath, BrokerSocketPathFailure> = when (val proof = route) {
        BrokerSocketRoute.Canonical -> Validation.validated(this)
        is BrokerSocketRoute.Aliased -> when (proof.receipt.validate()) {
            is Validation.Validated -> Validation.validated(this)
            is Validation.Rejected -> Validation.rejected(BrokerSocketPathFailure.ALIAS_REJECTED)
        }
        is BrokerSocketRoute.PrivateUpstream -> when (proof.receipt.validate()) {
            is Validation.Validated -> Validation.validated(this)
            is Validation.Rejected -> Validation.rejected(BrokerSocketPathFailure.UPSTREAM_DIRECTORY_REJECTED)
        }
    }

    override fun equals(other: Any?): Boolean = other is BrokerSocketPath &&
        path == other.path && physicalPath == other.physicalPath

    override fun hashCode(): Int = 31 * path.hashCode() + physicalPath.hashCode()

    companion object {
        internal fun admit(candidate: Path): Validation<BrokerSocketPath, BrokerSocketPathFailure> =
            when {
                !candidate.isAbsolute -> Validation.rejected(BrokerSocketPathFailure.NOT_ABSOLUTE)
                candidate.normalize() != candidate ->
                    Validation.rejected(BrokerSocketPathFailure.NOT_NORMALIZED)
                candidate.toString().toByteArray(StandardCharsets.UTF_8).size >= UNIX_PATH_BYTES ->
                    Validation.rejected(BrokerSocketPathFailure.TOO_LONG)
                else -> Validation.validated(BrokerSocketPath(candidate, candidate, BrokerSocketRoute.Canonical))
            }

        /** Creation happens only at the explicit installed-server startup boundary. */
        internal fun prepareInstalled(physicalSocket: Path): Validation<BrokerSocketPath, BrokerSocketPathFailure> {
            when (val direct = admit(physicalSocket)) {
                is Validation.Validated -> return direct
                is Validation.Rejected -> if (direct.failures.toList() != listOf(BrokerSocketPathFailure.TOO_LONG)) return direct
            }
            if (physicalSocket.fileName.toString() !in setOf("c.sock", "u.sock")) {
                return Validation.rejected(BrokerSocketPathFailure.ALIAS_REJECTED)
            }
            if (physicalSocket.fileName.toString() == "u.sock") {
                return when (
                    val directory = io.github.amichne.kast.appserver.BrokerUpstreamDirectories.prepare(
                        physicalSocket.parent,
                    )
                ) {
                    is Validation.Rejected -> Validation.rejected(
                        BrokerSocketPathFailure.UPSTREAM_DIRECTORY_REJECTED,
                    )
                    is Validation.Validated -> {
                        val path = directory.value.directory.path.resolve(physicalSocket.fileName)
                        when (val admitted = admit(path)) {
                            is Validation.Rejected -> admitted
                            is Validation.Validated -> Validation.validated(
                                BrokerSocketPath(
                                    admitted.value.path,
                                    admitted.value.physicalPath,
                                    BrokerSocketRoute.PrivateUpstream(directory.value),
                                ),
                            )
                        }
                    }
                }
            }
            return when (val alias = io.github.amichne.kast.appserver.BrokerEndpointAliases.prepare(physicalSocket.parent)) {
                is Validation.Rejected -> Validation.rejected(BrokerSocketPathFailure.ALIAS_REJECTED)
                is Validation.Validated -> Validation.validated(BrokerSocketPath(
                    alias.value.alias.resolve(physicalSocket.fileName), physicalSocket,
                    BrokerSocketRoute.Aliased(alias.value),
                ))
            }
        }

        /** External observation reconstructs only a declared exact ownership receipt. */
        internal fun observe(candidate: Path): Validation<BrokerSocketPath, BrokerSocketPathFailure> {
            if (!io.github.amichne.kast.appserver.BrokerEndpointAliases.isReservedSocket(candidate)) return admit(candidate)
            return when (val alias = io.github.amichne.kast.appserver.BrokerEndpointAliases.observe(candidate)) {
                is Validation.Rejected -> Validation.rejected(BrokerSocketPathFailure.ALIAS_REJECTED)
                is Validation.Validated -> Validation.validated(BrokerSocketPath(
                    candidate, alias.value.physicalDirectory.resolve(candidate.fileName), BrokerSocketRoute.Aliased(alias.value),
                ))
            }
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
    val connectionInitializationTimeoutMillis: Long = BrokerOperationalLimits.connectionInitialization.value,
    val bindingOwner: io.github.amichne.kast.appserver.protocol.ThreadBindingOwner = io.github.amichne.kast.appserver.protocol.ThreadBindingOwner.ProtocolFixture,
    val activitySink: BrokerInvocationActivitySink = BrokerInvocationActivitySink.Disabled,
    val sessionBootstrap: AgentSessionBootstrap? = null,
    val sessionActivitySink: SessionActivitySink = SessionActivitySink.Disabled,
    val qualification: BrokerQualification? = null,
    val invocationJournal: java.nio.file.Path? = null,
    val enrollment: io.github.amichne.kast.appserver.WorkspaceEnrollment = io.github.amichne.kast.appserver.WorkspaceEnrollment.ProtocolFixture,
)

internal enum class KtorBrokerServerFailure {
    INVALID_LIMIT,
    INVOCATION_STORE_REJECTED,
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
    private val ownershipLease: UnixSocketOwnershipLease,
    private val frontend: BrokerFrontend,
    private val runtimeControl: WorkspaceRuntimeControl?,
) {
    internal suspend fun close() {
        try {
            try { runtimeControl?.drain() }
            finally {
                try { frontend.close() }
                finally { engine.stopSuspend(gracePeriodMillis = BrokerOperationalLimits.serverShutdownGrace.value, timeoutMillis = BrokerOperationalLimits.serverShutdown.value) }
            }
        } finally {
            try {
                ownedSocket.retire()
            } finally {
                ownershipLease.close()
            }
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
            val frontend = when (val admission = frontend(options)) {
                is BrokerFrontendAdmission.Prepared -> admission.frontend
                BrokerFrontendAdmission.Rejected -> return KtorBrokerServerStart.Rejected(KtorBrokerServerFailure.INVOCATION_STORE_REJECTED)
            }
            return startTransport(options.publicSocket, options.maximumConnections, options.maximumMessageBytes, frontend, null).also {
                if (it is KtorBrokerServerStart.Rejected) frontend.close()
            }
        }

        internal suspend fun startCoordinator(
            socket: BrokerSocketPath,
            runtimeControl: WorkspaceRuntimeControl,
            frontend: BrokerFrontend,
        ): KtorBrokerServerStart = startTransport(socket, BrokerOperationalLimits.maximumConnections, BrokerOperationalLimits.maximumMessageBytes, frontend, runtimeControl)

        internal suspend fun frontend(options: KtorBrokerServerOptions, afterClose: suspend () -> Unit = {}): BrokerFrontendAdmission {
            val hub = BrokerSessionHub(options)
            if (hub.initialization() is InvocationAdmission.Rejected) {
                hub.close()
                return BrokerFrontendAdmission.Rejected
            }
            return BrokerFrontendAdmission.Prepared(object : BrokerFrontend {
                override suspend fun connect(session: DefaultWebSocketServerSession) = bridgeConnection(session, options, hub)
                override suspend fun close() { try { hub.close() } finally { afterClose() } }
            })
        }

        private suspend fun startTransport(
            socket: BrokerSocketPath,
            maximumConnections: Int,
            maximumMessageBytes: Int,
            frontend: BrokerFrontend,
            runtimeControl: WorkspaceRuntimeControl?,
        ): KtorBrokerServerStart {
            val ownershipLease = when (
                val acquisition = UnixSocketPathOwnership.acquireLease(socket)
            ) {
                is UnixSocketOwnershipLeaseAcquisition.Acquired -> acquisition.lease
                UnixSocketOwnershipLeaseAcquisition.Owned -> return KtorBrokerServerStart.Rejected(
                    KtorBrokerServerFailure.SOCKET_PATH_OWNED,
                )
                UnixSocketOwnershipLeaseAcquisition.Rejected ->
                    return KtorBrokerServerStart.Rejected(
                        KtorBrokerServerFailure.SOCKET_PATH_REJECTED,
                    )
                UnixSocketOwnershipLeaseAcquisition.ParentRejected ->
                    return KtorBrokerServerStart.Rejected(
                        KtorBrokerServerFailure.SOCKET_PARENT_REJECTED,
                    )
            }
            when (UnixSocketPathOwnership.prepare(socket)) {
                UnixSocketPathPreparation.PREPARED -> Unit
                UnixSocketPathPreparation.OWNED -> return rejectedAfterLease(
                    ownershipLease,
                    KtorBrokerServerFailure.SOCKET_PATH_OWNED,
                )
                UnixSocketPathPreparation.REJECTED -> return rejectedAfterLease(
                    ownershipLease,
                    KtorBrokerServerFailure.SOCKET_PATH_REJECTED,
                )
                UnixSocketPathPreparation.PARENT_REJECTED -> return rejectedAfterLease(
                    ownershipLease,
                    KtorBrokerServerFailure.SOCKET_PARENT_REJECTED,
                )
            }
            val connectionCount = AtomicInteger(0)
            val engine = embeddedServer(
                factory = CIO,
                configure = { unixConnector(socket.path.toString()) },
                module = {
                    install(WebSockets) {
                        maxFrameSize = maximumMessageBytes.toLong()
                    }
                    routing {
                        if (runtimeControl != null) {
                            val controlConnections = AtomicInteger(0)
                            webSocket("/kast-runtime") {
                                val count = controlConnections.incrementAndGet()
                                try {
                                    if (count > BrokerOperationalLimits.maximumRuntimeConnections) close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "runtime connection limit exceeded"))
                                    else runtimeControl.handle(this)
                                } finally { controlConnections.decrementAndGet() }
                            }
                        }
                        BrokerWebSocketRoute.entries.forEach { route ->
                            webSocket(route.path) {
                                val count = connectionCount.incrementAndGet()
                                try {
                                    if (count > maximumConnections) {
                                        close(
                                            CloseReason(
                                                CloseReason.Codes.TRY_AGAIN_LATER,
                                                "connection limit exceeded",
                                            ),
                                        )
                                    } else {
                                        frontend.connect(this)
                                    }
                                } finally {
                                    connectionCount.decrementAndGet()
                                }
                            }
                        }
                    }
                },
            )
            try {
                if (socket.revalidate() is Validation.Rejected) {
                    frontend.close()
                    return rejectedAfterLease(ownershipLease, KtorBrokerServerFailure.SOCKET_PARENT_REJECTED)
                }
                engine.startSuspend(wait = false)
                Files.setPosixFilePermissions(
                    socket.physicalPath,
                    PosixFilePermissions.fromString("rw-------"),
                )
            } catch (_: Exception) {
                engine.stopSuspend(gracePeriodMillis = 0, timeoutMillis = BrokerOperationalLimits.serverFailedStartupShutdown.value)
                ownershipLease.close()
                return KtorBrokerServerStart.Rejected(
                    KtorBrokerServerFailure.SERVER_START_REJECTED,
                )
            }
            val owned = OwnedUnixSocket.capture(socket)
                ?: run {
                    engine.stopSuspend(gracePeriodMillis = 0, timeoutMillis = BrokerOperationalLimits.serverFailedStartupShutdown.value)
                    ownershipLease.close()
                    return KtorBrokerServerStart.Rejected(
                        KtorBrokerServerFailure.SOCKET_IDENTITY_REJECTED,
                    )
                }
            return KtorBrokerServerStart.Started(
                KtorBrokerServer(engine, owned, ownershipLease, frontend, runtimeControl),
            )
        }

        private fun rejectedAfterLease(
            lease: UnixSocketOwnershipLease,
            failure: KtorBrokerServerFailure,
        ): KtorBrokerServerStart.Rejected {
            lease.close()
            return KtorBrokerServerStart.Rejected(failure)
        }

        private suspend fun bridgeConnection(
            downstream: DefaultWebSocketServerSession,
            options: KtorBrokerServerOptions,
            hub: BrokerSessionHub,
        ) {
            val initialize = receiveInitialize(downstream, options) ?: return
            val session = hub.attach(initialize.message) ?: run {
                downstream.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "session unavailable"))
                return
            }
            try {
                coroutineScope {
                    val input = launch {
                        for (frame in downstream.incoming) {
                            val message = (frame as? Frame.Text)?.readText() ?: break
                            if (message.utf8Bytes() > options.maximumMessageBytes) break
                            session.accept(message)
                        }
                    }
                    val output = launch {
                        for (message in session.output) downstream.send(message)
                    }
                    select<Unit> { input.onJoin { }; output.onJoin { } }
                    input.cancelAndJoin()
                    output.cancelAndJoin()
                }
            } finally {
                session.detach()
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

private enum class BrokerWebSocketRoute(val path: String) {
    CODEX("/rpc"),
    LEGACY("/"),
}
