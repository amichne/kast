package io.github.amichne.kast.indexer

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
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

enum class IndexerTransportFailure {
    SOCKET_PARENT_UNAVAILABLE,
    STATE_DIRECTORY_UNAVAILABLE,
    SOCKET_PATH_OCCUPIED,
    SOCKET_BIND_FAILED,
    ENDPOINT_DESCRIPTOR_UNAVAILABLE,
}

sealed interface IndexerEndpointPreparation {
    data class Prepared(
        val endpoint: PreparedIndexerEndpoint,
    ) : IndexerEndpointPreparation

    data class Rejected(
        val failure: IndexerTransportFailure,
    ) : IndexerEndpointPreparation
}

/** Canonical runtime state and exact marker paths proven absent, but not yet published as ready. */
class PreparedIndexerEndpoint private constructor(
    internal val options: IndexerLaunchOptions,
    val stateDirectory: Path,
) {
    companion object {
        /**
         * Proof transition: `IndexerLaunchOptions -> IndexerEndpointPreparation`.
         *
         * A prepared endpoint establishes a canonical, non-symlinked state directory plus absent
         * exact socket and descriptor markers. It owns no bound socket and cannot advertise
         * readiness. [IndexerTransportFailure] is the closed expected failure. Raw paths leave
         * only for filesystem state preparation and installed runtime construction.
         */
        fun prepare(options: IndexerLaunchOptions): IndexerEndpointPreparation {
            val socketPath = options.socketPath
            val socketParent = socketPath.parent
                ?: return IndexerEndpointPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE,
                )
            val canonicalStateParent = try {
                Files.createDirectories(socketParent)
                socketParent.toRealPath()
            } catch (_: IOException) {
                return IndexerEndpointPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE,
                )
            } catch (_: SecurityException) {
                return IndexerEndpointPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE,
                )
            }
            // Keep the AF_UNIX address exactly as admitted. Only runtime-owned state uses the
            // physical parent; macOS aliases can lengthen a canonical address past its limit.
            val statePath = canonicalStateParent.resolve("${socketPath.fileName}.state")
            val stateDirectory = when (val preparation = prepareStateDirectory(statePath)) {
                is StateDirectoryPreparation.Prepared -> preparation.path
                StateDirectoryPreparation.Rejected -> return IndexerEndpointPreparation.Rejected(
                    IndexerTransportFailure.STATE_DIRECTORY_UNAVAILABLE,
                )
            }
            when (admitSocketPath(socketPath)) {
                SocketPathAdmission.Available -> Unit
                SocketPathAdmission.Occupied -> return IndexerEndpointPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_PATH_OCCUPIED,
                )
            }
            when (retireEndpointDescriptor(socketPath.endpointDescriptorPath())) {
                EndpointDescriptorRetirement.Retired -> Unit
                EndpointDescriptorRetirement.Rejected ->
                    return IndexerEndpointPreparation.Rejected(
                        IndexerTransportFailure.ENDPOINT_DESCRIPTOR_UNAVAILABLE,
                    )
            }
            return IndexerEndpointPreparation.Prepared(
                PreparedIndexerEndpoint(options, stateDirectory),
            )
        }
    }
}

sealed interface IndexerTransportActivation {
    data class Activated(
        val transport: InstalledIndexerTransport,
    ) : IndexerTransportActivation

    data class Rejected(
        val failure: IndexerTransportFailure,
    ) : IndexerTransportActivation
}

enum class IndexerConnectionFailure {
    ACCEPT_FAILED,
    INVALID_REQUEST_FRAME,
    DISPATCH_REJECTED,
    RESPONSE_WRITE_FAILED,
}

sealed interface IndexerConnectionHandling {
    data object Served : IndexerConnectionHandling

    data class Rejected(
        val failure: IndexerConnectionFailure,
    ) : IndexerConnectionHandling
}

/** Bound exact-root ready transport carrying its already-created runtime host. */
class InstalledIndexerTransport private constructor(
    private val server: ServerSocketChannel,
    private val socketPath: Path,
    private val descriptorPath: Path,
    private val host: KastIndexerHost,
) : AutoCloseable {
    /**
     * Proof transition: `one accepted socket -> IndexerConnectionHandling`.
     *
     * Establishes zero or more bounded request/response exchanges through the host captured at
     * activation. [IndexerConnectionFailure] is the closed expected failure. Raw documents may
     * cross only the frame codec and [KastIndexerHost].
     */
    fun serveNext(): IndexerConnectionHandling {
        val channel = try {
            server.accept()
        } catch (_: IOException) {
            return IndexerConnectionHandling.Rejected(IndexerConnectionFailure.ACCEPT_FAILED)
        }
        return channel.use(::serveFrames)
    }

    private fun serveFrames(connection: SocketChannel): IndexerConnectionHandling {
        while (true) {
            val request = when (val frame = IndexerWireFrameCodec.read(connection)) {
                is IndexerFrameRead.Received -> frame.document
                IndexerFrameRead.EndOfStream -> return IndexerConnectionHandling.Served
                IndexerFrameRead.Rejected -> return IndexerConnectionHandling.Rejected(
                    IndexerConnectionFailure.INVALID_REQUEST_FRAME,
                )
            }
            when (val dispatch = awaitDispatch { host.dispatch(request) }) {
                is KastRuntimeDispatch.Responded -> when (
                    IndexerWireFrameCodec.write(connection, dispatch.document)
                ) {
                    IndexerFrameWrite.Written -> Unit
                    IndexerFrameWrite.Rejected -> return IndexerConnectionHandling.Rejected(
                        IndexerConnectionFailure.RESPONSE_WRITE_FAILED,
                    )
                }
                is KastRuntimeDispatch.Rejected -> return IndexerConnectionHandling.Rejected(
                    IndexerConnectionFailure.DISPATCH_REJECTED,
                )
            }
        }
    }

    fun serve(): Nothing {
        while (true) serveNext()
    }

    override fun close() {
        try {
            server.close()
        } finally {
            deleteEndpointDescriptor(descriptorPath)
            deleteOwnedSocket(socketPath)
        }
    }

    companion object {
        /**
         * Proof transition: `PreparedIndexerEndpoint + KastIndexerHost -> IndexerTransportActivation`.
         *
         * Activation establishes a bound socket and atomically published descriptor whose
         * captured host already owns a created runtime dispatch. [IndexerTransportFailure] closes
         * races, bind failures, and descriptor publication failure. Raw paths leave only at the
         * JDK socket and filesystem boundaries.
         */
        fun activate(
            prepared: PreparedIndexerEndpoint,
            host: KastIndexerHost,
        ): IndexerTransportActivation {
            val socketPath = prepared.options.socketPath
            when (admitSocketPath(socketPath)) {
                SocketPathAdmission.Available -> Unit
                SocketPathAdmission.Occupied -> return IndexerTransportActivation.Rejected(
                    IndexerTransportFailure.SOCKET_PATH_OCCUPIED,
                )
            }
            val server = try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    bind(UnixDomainSocketAddress.of(socketPath))
                }
            } catch (_: IOException) {
                return IndexerTransportActivation.Rejected(
                    IndexerTransportFailure.SOCKET_BIND_FAILED,
                )
            } catch (_: UnsupportedOperationException) {
                return IndexerTransportActivation.Rejected(
                    IndexerTransportFailure.SOCKET_BIND_FAILED,
                )
            } catch (_: SecurityException) {
                return IndexerTransportActivation.Rejected(
                    IndexerTransportFailure.SOCKET_BIND_FAILED,
                )
            }
            val descriptor = when (
                val publication = publishEndpointDescriptor(prepared.options)
            ) {
                is EndpointDescriptorPublication.Published -> publication.path
                EndpointDescriptorPublication.Rejected -> {
                    try {
                        server.close()
                    } finally {
                        deleteOwnedSocket(socketPath)
                    }
                    return IndexerTransportActivation.Rejected(
                        IndexerTransportFailure.ENDPOINT_DESCRIPTOR_UNAVAILABLE,
                    )
                }
            }
            return IndexerTransportActivation.Activated(
                InstalledIndexerTransport(server, socketPath, descriptor, host),
            )
        }
    }
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
private fun prepareStateDirectory(path: Path): StateDirectoryPreparation = try {
    if (Files.isSymbolicLink(path)) return StateDirectoryPreparation.Rejected
    Files.createDirectories(path)
    val canonical = path.toRealPath()
    if (canonical == path && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
        StateDirectoryPreparation.Prepared(canonical)
    } else {
        StateDirectoryPreparation.Rejected
    }
} catch (_: IOException) {
    StateDirectoryPreparation.Rejected
} catch (_: SecurityException) {
    StateDirectoryPreparation.Rejected
}

/** Refines an exact socket path into absent or occupied without deleting non-socket entries. */
private fun admitSocketPath(path: Path): SocketPathAdmission {
    val attributes = try {
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
private fun socketReachability(path: Path): SocketReachability = try {
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

private fun deleteOwnedSocket(path: Path) {
    try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.isOther) Files.deleteIfExists(path)
    } catch (_: IOException) {
    } catch (_: SecurityException) {
    }
}

private fun awaitDispatch(block: suspend () -> KastRuntimeDispatch): KastRuntimeDispatch {
    val completion = CompletableFuture<KastRuntimeDispatch>()
    block.startCoroutine(
        object : Continuation<KastRuntimeDispatch> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<KastRuntimeDispatch>) {
                result.fold(completion::complete, completion::completeExceptionally)
            }
        },
    )
    return completion.join()
}
