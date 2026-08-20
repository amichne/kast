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
import java.util.concurrent.CountDownLatch
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

sealed interface IndexerTransportPreparation {
    data class Prepared(
        val transport: InstalledIndexerTransport,
    ) : IndexerTransportPreparation

    data class Rejected(
        val failure: IndexerTransportFailure,
    ) : IndexerTransportPreparation
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

/** Bound exact-root socket and canonical runtime-owned state directory. */
class InstalledIndexerTransport private constructor(
    private val server: ServerSocketChannel,
    private val socketPath: Path,
    private val descriptorPath: Path,
    val stateDirectory: Path,
) : AutoCloseable {
    /**
     * Proof transition: `KastIndexerHost + one accepted socket -> IndexerConnectionHandling`.
     *
     * Establishes zero or more bounded request/response exchanges until clean connection closure.
     * [IndexerConnectionFailure] is the closed expected failure. Raw documents may cross only the
     * frame codec and [KastIndexerHost].
     */
    fun serveNext(host: KastIndexerHost): IndexerConnectionHandling {
        val channel = try {
            server.accept()
        } catch (_: IOException) {
            return IndexerConnectionHandling.Rejected(IndexerConnectionFailure.ACCEPT_FAILED)
        }
        return channel.use { connection -> serveFrames(connection, host) }
    }

    private fun serveFrames(
        connection: SocketChannel,
        host: KastIndexerHost,
    ): IndexerConnectionHandling {
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

    fun serve(host: KastIndexerHost): Nothing {
        while (true) serveNext(host)
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
         * Proof transition: `IndexerLaunchOptions -> IndexerTransportPreparation`.
         *
         * Establishes a bound Unix-domain server and a canonical, non-symlinked state directory
         * derived only from the admitted socket. [IndexerTransportFailure] is the closed expected
         * failure. Raw paths may leave only for JDK socket and filesystem effects.
         */
        fun prepare(options: IndexerLaunchOptions): IndexerTransportPreparation {
            val socketPath = options.socketPath
            val socketParent = socketPath.parent
                               ?: return IndexerTransportPreparation.Rejected(
                                   IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE,
                               )
            val canonicalStateParent = try {
                Files.createDirectories(socketParent)
                socketParent.toRealPath()
            } catch (_: IOException) {
                return IndexerTransportPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE,
                )
            } catch (_: SecurityException) {
                return IndexerTransportPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_PARENT_UNAVAILABLE,
                )
            }
            // Keep the AF_UNIX address exactly as admitted. Only runtime-owned state uses the
            // physical parent; macOS aliases can lengthen a canonical socket address past its limit.
            val statePath = canonicalStateParent.resolve("${socketPath.fileName}.state")
            val stateDirectory = when (val preparation = prepareStateDirectory(statePath)) {
                is StateDirectoryPreparation.Prepared -> preparation.path
                StateDirectoryPreparation.Rejected -> return IndexerTransportPreparation.Rejected(
                    IndexerTransportFailure.STATE_DIRECTORY_UNAVAILABLE,
                )
            }
            when (admitSocketPath(socketPath)) {
                SocketPathAdmission.Available -> Unit
                SocketPathAdmission.Occupied -> return IndexerTransportPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_PATH_OCCUPIED,
                )
            }
            val server = try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    bind(UnixDomainSocketAddress.of(socketPath))
                }
            } catch (_: IOException) {
                return IndexerTransportPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_BIND_FAILED,
                )
            } catch (_: UnsupportedOperationException) {
                return IndexerTransportPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_BIND_FAILED,
                )
            } catch (_: SecurityException) {
                return IndexerTransportPreparation.Rejected(
                    IndexerTransportFailure.SOCKET_BIND_FAILED,
                )
            }
            val descriptorPath = when (val publication = publishEndpointDescriptor(options)) {
                is EndpointDescriptorPublication.Published -> publication.path
                EndpointDescriptorPublication.Rejected -> {
                    try {
                        server.close()
                    } finally {
                        deleteOwnedSocket(socketPath)
                    }
                    return IndexerTransportPreparation.Rejected(
                        IndexerTransportFailure.ENDPOINT_DESCRIPTOR_UNAVAILABLE,
                    )
                }
            }
            return IndexerTransportPreparation.Prepared(
                InstalledIndexerTransport(server, socketPath, descriptorPath, stateDirectory),
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

/**
 * Proof transition: `Path -> StateDirectoryPreparation`.
 *
 * Establishes one physically canonical, non-symlinked owned state directory.
 * [StateDirectoryPreparation.Rejected] is the closed expected failure. The raw path may leave only
 * for filesystem creation and installed runtime construction.
 */
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

/**
 * Proof transition: `Path -> SocketPathAdmission`.
 *
 * Establishes that the exact socket path is absent after rejecting active and non-socket entries
 * and removing only an unreachable socket entry. [SocketPathAdmission.Occupied] is the closed
 * expected failure. The raw path may leave only for JDK socket and filesystem effects.
 */
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
    if (
        !attributes.isOther ||
        socketReachability(path) is SocketReachability.Reachable
    ) {
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

/**
 * Proof transition: `Path -> SocketReachability`.
 *
 * Establishes one closed connection observation for a pre-existing Unix socket.
 * [SocketReachability.Unreachable] closes failed connection observations. The raw path may leave
 * only for the JDK Unix-domain connection boundary.
 */
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
    val completion = CountDownLatch(1)
    var completionResult: Result<KastRuntimeDispatch>? = null
    block.startCoroutine(
        object : Continuation<KastRuntimeDispatch> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<KastRuntimeDispatch>) {
                completionResult = result
                completion.countDown()
            }
        },
    )
    completion.await()
    return checkNotNull(completionResult).getOrThrow()
}
