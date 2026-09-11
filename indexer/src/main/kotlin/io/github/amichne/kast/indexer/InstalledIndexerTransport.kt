package io.github.amichne.kast.indexer

import io.github.amichne.kast.distribution.contract.WirePeerQualification
import io.github.amichne.kast.distribution.contract.WireRuntimeQualification
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.runtime.composition.KastRuntimeDispatch
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class IndexerTransportFailure {
    SOCKET_PARENT_UNAVAILABLE,
    STATE_DIRECTORY_UNAVAILABLE,
    SOCKET_PATH_OCCUPIED,
    SOCKET_BIND_FAILED,
    ENDPOINT_DESCRIPTOR_UNAVAILABLE,
    SHUTDOWN_HOOK_UNAVAILABLE,
}

sealed interface IndexerEndpointPreparation {
    data class Prepared(val endpoint: PreparedIndexerEndpoint) : IndexerEndpointPreparation

    data class Rejected(val failure: IndexerTransportFailure) : IndexerEndpointPreparation
}

/** Canonical runtime state and exact marker paths proven absent, but not yet published as ready. */
class PreparedIndexerEndpoint
private constructor(
    internal val options: IndexerLaunchOptions,
    val stateDirectory: Path,
    internal val route: IndexerSocketRoute,
) {
    companion object {
        /**
         * Proof transition: `IndexerLaunchOptions -> IndexerEndpointPreparation`.
         *
         * A prepared endpoint establishes a canonical, non-symlinked state directory plus absent exact socket and
         * descriptor markers. It owns no bound socket and cannot advertise readiness. [IndexerTransportFailure] is the
         * closed expected failure. Raw paths leave only for filesystem state preparation and installed runtime
         * construction.
         */
        fun prepare(options: IndexerLaunchOptions): IndexerEndpointPreparation {
            val socketPath = options.socketPath
            val route =
                when (val admission = IndexerSocketRoute.prepare(socketPath)) {
                    is Validation.Validated -> admission.value
                    is Validation.Rejected ->
                        return IndexerEndpointPreparation.Rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
                }
            val socketParent = route.physical.parent
            val canonicalStateParent =
                try {
                    Files.createDirectories(socketParent)
                    Files.setPosixFilePermissions(
                        socketParent,
                        PosixFilePermissions.fromString("rwx------"),
                    )
                    socketParent.toRealPath()
                } catch (_: IOException) {
                    return IndexerEndpointPreparation.Rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
                } catch (_: UnsupportedOperationException) {
                    return IndexerEndpointPreparation.Rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
                } catch (_: SecurityException) {
                    return IndexerEndpointPreparation.Rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
                }
            // Keep the AF_UNIX address exactly as admitted. Only runtime-owned state uses the
            // physical parent; macOS aliases can lengthen a canonical address past its limit.
            val statePath = canonicalStateParent.resolve("${socketPath.fileName}.state")
            val stateDirectory =
                when (val preparation = prepareStateDirectory(statePath)) {
                    is StateDirectoryPreparation.Prepared -> preparation.path
                    StateDirectoryPreparation.Rejected ->
                        return IndexerEndpointPreparation.Rejected(IndexerTransportFailure.STATE_DIRECTORY_UNAVAILABLE)
                }
            if (route.validate() is Validation.Rejected)
                return IndexerEndpointPreparation.Rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
            when (admitSocketPath(socketPath)) {
                SocketPathAdmission.Available -> Unit
                SocketPathAdmission.Occupied ->
                    return IndexerEndpointPreparation.Rejected(IndexerTransportFailure.SOCKET_PATH_OCCUPIED)
            }
            when (retireEndpointDescriptor(route.physical.endpointDescriptorPath())) {
                EndpointDescriptorRetirement.Retired -> Unit
                EndpointDescriptorRetirement.Rejected ->
                    return IndexerEndpointPreparation.Rejected(IndexerTransportFailure.ENDPOINT_DESCRIPTOR_UNAVAILABLE)
            }
            return IndexerEndpointPreparation.Prepared(PreparedIndexerEndpoint(options, stateDirectory, route))
        }
    }
}

sealed interface IndexerTransportActivation {
    data class Activated(val transport: InstalledIndexerTransport) : IndexerTransportActivation

    data class Rejected(val failure: IndexerTransportFailure) : IndexerTransportActivation
}

enum class IndexerConnectionFailure {
    ACCEPT_FAILED,
    INVALID_REQUEST_FRAME,
    DISPATCH_REJECTED,
    RESPONSE_WRITE_FAILED,
    CONNECTION_CAPACITY_EXCEEDED,
    FRAME_READ_DEADLINE_EXCEEDED,
    FRAME_WRITE_DEADLINE_EXCEEDED,
    SEMANTIC_LANE_BUSY,
    DISPATCH_DEADLINE_EXCEEDED,
    REQUEST_CANCELLED,
    RECOVERY_REQUIRED,
    TRANSPORT_CLOSED,
    PEER_QUALIFICATION_REJECTED,
}

sealed interface IndexerConnectionHandling {
    /** Admission owns the channel until a worker emits its terminal observation. */
    data object Admitted : IndexerConnectionHandling

    data object Served : IndexerConnectionHandling

    data class Rejected(val failure: IndexerConnectionFailure) : IndexerConnectionHandling
}

/** Bound exact-root ready transport carrying its already-created runtime host. */
class InstalledIndexerTransport
private constructor(
    private val server: ServerSocketChannel,
    private val route: IndexerSocketRoute,
    private val socket: IndexerOwnedFile,
    private val descriptor: IndexerOwnedFile,
    host: KastIndexerHost,
    private val policy: IndexerRequestPolicy,
    private val activity: IndexerRequestActivitySink,
    private val authority: IndexerWireAuthority,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val shutdownHookRegistered = AtomicBoolean(false)
    private val shutdownHook = Thread(::close, "kast-indexer-transport-shutdown")
    private val capacity = Semaphore(policy.connections.value)
    private val connections = ConcurrentHashMap.newKeySet<SocketChannel>()
    private val workers =
        ThreadPoolExecutor(
            policy.connections.value,
            policy.connections.value,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(policy.connections.value),
            Thread.ofPlatform()
                .name("kast-indexer-connection-", 0)
                .daemon(true)
                .inheritInheritableThreadLocals(false)
                .factory(),
        )
    private val semantic = IndexerSemanticLane(host, policy, activity)

    /**
     * Proof transition: `one accepted socket -> IndexerConnectionHandling`.
     *
     * Establishes bounded admission independently of request framing and semantic execution. The admitted worker owns
     * its channel until terminal activity; no incomplete client holds the accept thread. Raw documents cross only the
     * frame codec and [KastIndexerHost].
     */
    fun serveNext(): IndexerConnectionHandling {
        if (closed.get()) return rejected(IndexerConnectionFailure.TRANSPORT_CLOSED)
        val channel =
            try {
                server.accept()
            } catch (_: IOException) {
                return rejected(
                    if (closed.get()) IndexerConnectionFailure.TRANSPORT_CLOSED
                    else IndexerConnectionFailure.ACCEPT_FAILED
                )
            }
        if (closed.get() || !capacity.tryAcquire()) {
            channel.close()
            observe(IndexerRequestStage.CONNECTION_ADMISSION, IndexerRequestOutcome.CAPACITY_EXCEEDED)
            return rejected(
                if (closed.get()) IndexerConnectionFailure.TRANSPORT_CLOSED
                else IndexerConnectionFailure.CONNECTION_CAPACITY_EXCEEDED
            )
        }
        connections.add(channel)
        try {
            workers.execute {
                try {
                    channel.use { connection ->
                        if (!closed.get()) serveFrames(connection)
                    }
                } finally {
                    connections.remove(channel)
                    capacity.release()
                }
            }
        } catch (_: RejectedExecutionException) {
            connections.remove(channel)
            capacity.release()
            channel.close()
            observe(IndexerRequestStage.CONNECTION_ADMISSION, IndexerRequestOutcome.REJECTED)
            return rejected(IndexerConnectionFailure.TRANSPORT_CLOSED)
        }
        observe(IndexerRequestStage.CONNECTION_ADMISSION, IndexerRequestOutcome.COMPLETED)
        return IndexerConnectionHandling.Admitted
    }

    private fun serveFrames(connection: SocketChannel): IndexerConnectionHandling {
        val input = IndexerConnectionInput(connection)
        when (val selected = authority) {
            IndexerWireAuthority.Fixture -> Unit
            is IndexerWireAuthority.Installed -> {
                val request = IndexerWireFrameCodec.read(connection, policy.frameRead, input)
                if (
                    request !is IndexerFrameRead.Received ||
                        WireRuntimeQualification.admitRequest(request.document, selected.identity) !=
                            WirePeerQualification.QUALIFIED
                ) {
                    observe(IndexerRequestStage.PEER_QUALIFICATION, IndexerRequestOutcome.REJECTED)
                    return rejected(IndexerConnectionFailure.PEER_QUALIFICATION_REJECTED)
                }
                if (
                    IndexerWireFrameCodec.write(
                        connection,
                        WireRuntimeQualification.response(selected.identity),
                        policy.frameWrite,
                    ) != IndexerFrameWrite.Written
                ) {
                    observe(IndexerRequestStage.PEER_QUALIFICATION, IndexerRequestOutcome.REJECTED)
                    return rejected(IndexerConnectionFailure.PEER_QUALIFICATION_REJECTED)
                }
                observe(IndexerRequestStage.PEER_QUALIFICATION, IndexerRequestOutcome.COMPLETED)
            }
        }
        while (!closed.get()) {
            observe(IndexerRequestStage.FRAME_READ, IndexerRequestOutcome.STARTED)
            val request =
                when (val frame = IndexerWireFrameCodec.read(connection, policy.frameRead, input)) {
                    is IndexerFrameRead.Received -> {
                        observe(IndexerRequestStage.FRAME_READ, IndexerRequestOutcome.COMPLETED)
                        frame.document
                    }
                    IndexerFrameRead.EndOfStream -> {
                        observe(IndexerRequestStage.FRAME_READ, IndexerRequestOutcome.COMPLETED)
                        return IndexerConnectionHandling.Served
                    }
                    IndexerFrameRead.Rejected -> {
                        observe(IndexerRequestStage.FRAME_READ, IndexerRequestOutcome.REJECTED)
                        return rejected(IndexerConnectionFailure.INVALID_REQUEST_FRAME)
                    }
                    IndexerFrameRead.TimedOut -> {
                        observe(IndexerRequestStage.FRAME_READ, IndexerRequestOutcome.DEADLINE_EXCEEDED)
                        return rejected(IndexerConnectionFailure.FRAME_READ_DEADLINE_EXCEEDED)
                    }
                }
            val dispatch =
                when (val execution = semantic.dispatch(request, input)) {
                    is IndexerSemanticExecution.Completed -> execution.dispatch
                    IndexerSemanticExecution.Busy -> return rejected(IndexerConnectionFailure.SEMANTIC_LANE_BUSY)
                    IndexerSemanticExecution.DeadlineExceeded ->
                        return rejected(IndexerConnectionFailure.DISPATCH_DEADLINE_EXCEEDED)
                    IndexerSemanticExecution.Cancelled -> return rejected(IndexerConnectionFailure.REQUEST_CANCELLED)
                    IndexerSemanticExecution.Rejected -> return rejected(IndexerConnectionFailure.DISPATCH_REJECTED)
                    IndexerSemanticExecution.RecoveryRequired ->
                        return rejected(IndexerConnectionFailure.RECOVERY_REQUIRED)
                }
            when (dispatch) {
                is KastRuntimeDispatch.Responded -> {
                    observe(IndexerRequestStage.FRAME_WRITE, IndexerRequestOutcome.STARTED)
                    when (IndexerWireFrameCodec.write(connection, dispatch.document, policy.frameWrite)) {
                        IndexerFrameWrite.Written ->
                            observe(IndexerRequestStage.FRAME_WRITE, IndexerRequestOutcome.COMPLETED)
                        IndexerFrameWrite.Rejected -> {
                            observe(IndexerRequestStage.FRAME_WRITE, IndexerRequestOutcome.REJECTED)
                            return rejected(IndexerConnectionFailure.RESPONSE_WRITE_FAILED)
                        }
                        IndexerFrameWrite.TimedOut -> {
                            observe(IndexerRequestStage.FRAME_WRITE, IndexerRequestOutcome.DEADLINE_EXCEEDED)
                            return rejected(IndexerConnectionFailure.FRAME_WRITE_DEADLINE_EXCEEDED)
                        }
                    }
                }
                is KastRuntimeDispatch.Rejected -> return rejected(IndexerConnectionFailure.DISPATCH_REJECTED)
            }
        }
        return rejected(IndexerConnectionFailure.TRANSPORT_CLOSED)
    }

    fun serve() {
        while (!closed.get()) {
            val admission = serveNext()
            if (
                admission is IndexerConnectionHandling.Rejected &&
                    admission.failure == IndexerConnectionFailure.ACCEPT_FAILED
            ) {
                close()
                return
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        observe(IndexerRequestStage.TRANSPORT_CLOSE, IndexerRequestOutcome.STARTED)
        if (shutdownHookRegistered.compareAndSet(true, false)) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
                // JVM shutdown owns this invocation of the registered hook.
            } catch (_: SecurityException) {
                // Closure continues; the idempotent hook may be invoked again.
            }
        }
        try {
            server.close()
        } finally {
            connections.forEach { connection ->
                try {
                    connection.close()
                } catch (_: IOException) {}
            }
            connections.clear()
            workers.shutdownNow()
            val retirement = semantic.close()
            val interrupted = Thread.interrupted()
            val workersRetired =
                try {
                    workers.awaitTermination(policy.retirement.value, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            if (
                retirement == IndexerSemanticRetirement.PROVEN &&
                    workersRetired &&
                    route.validate() is Validation.Validated
            ) {
                val descriptorRetirement = descriptor.retire()
                val socketRetirement = socket.retire()
                observe(
                    IndexerRequestStage.TRANSPORT_CLOSE,
                    if (
                        descriptorRetirement == IndexerArtifactRetirement.PROVEN &&
                            socketRetirement == IndexerArtifactRetirement.PROVEN
                    )
                        IndexerRequestOutcome.COMPLETED
                    else IndexerRequestOutcome.RECOVERY_REQUIRED,
                )
            } else {
                // A closed listening channel is not proof of retired semantic work or worker ownership.
                observe(IndexerRequestStage.TRANSPORT_CLOSE, IndexerRequestOutcome.RECOVERY_REQUIRED)
            }
        }
    }

    private fun installShutdownHook(): Boolean =
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            shutdownHookRegistered.set(true)
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }

    companion object {
        /**
         * Proof transition: `PreparedIndexerEndpoint + KastIndexerHost -> IndexerTransportActivation`.
         *
         * Activation establishes a bound socket and atomically published descriptor whose captured host already owns a
         * created runtime dispatch. [IndexerTransportFailure] closes races, bind failures, and descriptor publication
         * failure. Raw paths leave only at the JDK socket and filesystem boundaries.
         */
        fun activate(
            prepared: PreparedIndexerEndpoint,
            host: KastIndexerHost,
            policy: IndexerRequestPolicy = IndexerRequestPolicy.Default,
            activity: IndexerRequestActivitySink = IndexerRequestActivitySink.StandardError,
            authority: IndexerWireAuthority,
        ): IndexerTransportActivation {
            val socketPath = prepared.options.socketPath
            if (prepared.route.validate() is Validation.Rejected)
                return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
            when (admitSocketPath(socketPath)) {
                SocketPathAdmission.Available -> Unit
                SocketPathAdmission.Occupied ->
                    return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_PATH_OCCUPIED)
            }
            val server =
                try {
                    ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                        bind(UnixDomainSocketAddress.of(socketPath))
                    }
                } catch (_: IOException) {
                    return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_BIND_FAILED)
                } catch (_: UnsupportedOperationException) {
                    return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_BIND_FAILED)
                } catch (_: SecurityException) {
                    return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_BIND_FAILED)
                }
            if (prepared.route.validate() is Validation.Rejected) {
                server.close()
                return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
            }
            val ownedSocket =
                when (val captured = IndexerOwnedFile.capture(prepared.route.physical)) {
                    is Validation.Validated -> captured.value
                    is Validation.Rejected -> {
                        server.close()
                        return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_BIND_FAILED)
                    }
                }
            try {
                Files.setPosixFilePermissions(
                    prepared.route.physical,
                    PosixFilePermissions.fromString("rw-------"),
                )
            } catch (_: IOException) {
                server.close()
                ownedSocket.retire()
                return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_BIND_FAILED)
            } catch (_: UnsupportedOperationException) {
                server.close()
                ownedSocket.retire()
                return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_BIND_FAILED)
            } catch (_: SecurityException) {
                server.close()
                ownedSocket.retire()
                return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_BIND_FAILED)
            }
            val descriptor =
                when (val publication = publishEndpointDescriptor(prepared.options, prepared.route.physical)) {
                    is EndpointDescriptorPublication.Published -> publication.path
                    EndpointDescriptorPublication.Rejected -> {
                        try {
                            server.close()
                        } finally {
                            ownedSocket.retire()
                        }
                        return IndexerTransportActivation.Rejected(
                            IndexerTransportFailure.ENDPOINT_DESCRIPTOR_UNAVAILABLE
                        )
                    }
                }
            val ownedDescriptor =
                when (val captured = IndexerOwnedFile.capture(descriptor)) {
                    is Validation.Validated -> captured.value
                    is Validation.Rejected -> {
                        server.close()
                        ownedSocket.retire()
                        return IndexerTransportActivation.Rejected(
                            IndexerTransportFailure.ENDPOINT_DESCRIPTOR_UNAVAILABLE
                        )
                    }
                }
            if (prepared.route.validate() is Validation.Rejected) {
                server.close()
                return IndexerTransportActivation.Rejected(IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE)
            }
            val transport =
                InstalledIndexerTransport(
                    server,
                    prepared.route,
                    ownedSocket,
                    ownedDescriptor,
                    host,
                    policy,
                    activity,
                    authority,
                )
            if (!transport.installShutdownHook()) {
                transport.close()
                return IndexerTransportActivation.Rejected(IndexerTransportFailure.SHUTDOWN_HOOK_UNAVAILABLE)
            }
            return IndexerTransportActivation.Activated(transport)
        }
    }

    private fun observe(stage: IndexerRequestStage, outcome: IndexerRequestOutcome) =
        activity.observe(IndexerRequestActivity(stage, outcome))
}

private sealed interface SocketPathAdmission {
    data object Available : SocketPathAdmission

    data object Occupied : SocketPathAdmission
}

private sealed interface StateDirectoryPreparation {
    data class Prepared(val path: Path) : StateDirectoryPreparation

    data object Rejected : StateDirectoryPreparation
}

/** Refines a path into one physically canonical, non-symlinked owned state directory. */
private fun prepareStateDirectory(path: Path): StateDirectoryPreparation =
    try {
        if (Files.isSymbolicLink(path)) return StateDirectoryPreparation.Rejected
        Files.createDirectories(path)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        val canonical = path.toRealPath()
        if (canonical == path && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
            StateDirectoryPreparation.Prepared(canonical)
        } else {
            StateDirectoryPreparation.Rejected
        }
    } catch (_: IOException) {
        StateDirectoryPreparation.Rejected
    } catch (_: UnsupportedOperationException) {
        StateDirectoryPreparation.Rejected
    } catch (_: SecurityException) {
        StateDirectoryPreparation.Rejected
    }

/** Refines an exact socket path into absent or occupied without deleting non-socket entries. */
private fun admitSocketPath(path: Path): SocketPathAdmission {
    val attributes =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) {
            return SocketPathAdmission.Available
        } catch (_: IOException) {
            return SocketPathAdmission.Occupied
        } catch (_: SecurityException) {
            return SocketPathAdmission.Occupied
        }
    if (!attributes.isOther || socketReachability(path) is SocketReachability.Reachable) {
        return SocketPathAdmission.Occupied
    }
    return try {
        Files.delete(path)
        SocketPathAdmission.Available
    } catch (_: IOException) {
        SocketPathAdmission.Occupied
    } catch (_: SecurityException) {
        SocketPathAdmission.Occupied
    }
}

private sealed interface SocketReachability {
    data object Reachable : SocketReachability

    data object Unreachable : SocketReachability
}

/** Observes one closed connection state for a pre-existing Unix socket. */
private fun socketReachability(path: Path): SocketReachability =
    try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
            socket.connect(UnixDomainSocketAddress.of(path))
        }
        SocketReachability.Reachable
    } catch (_: IOException) {
        SocketReachability.Unreachable
    } catch (_: UnsupportedOperationException) {
        SocketReachability.Unreachable
    } catch (_: SecurityException) {
        SocketReachability.Unreachable
    }

private fun rejected(failure: IndexerConnectionFailure): IndexerConnectionHandling =
    IndexerConnectionHandling.Rejected(failure)
