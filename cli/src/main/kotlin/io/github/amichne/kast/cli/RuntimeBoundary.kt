package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** An exact-root UDS endpoint. */
class RuntimeEndpoint private constructor(
    val root: CanonicalRoot,
    internal val socketPath: Path,
) {
    override fun equals(other: Any?): Boolean =
        other is RuntimeEndpoint && root == other.root && socketPath == other.socketPath

    override fun hashCode(): Int = 31 * root.hashCode() + socketPath.hashCode()

    companion object {
        /**
         * Proof transition: `CanonicalRoot + Path -> RuntimeEndpointResolution`.
         *
         * Establishes an absolute normalized socket endpoint bound to the exact canonical root.
         * [RuntimeEndpointFailure] is the closed expected failure. The raw socket path may be
         * extracted only by the process and UDS adapters.
         */
        fun at(root: CanonicalRoot, socket: Path): RuntimeEndpointResolution {
            if (!socket.isAbsolute) {
                return RuntimeEndpointResolution.Rejected(
                    RuntimeEndpointFailure.INVALID_SOCKET_PATH,
                )
            }
            return RuntimeEndpointResolution.Resolved(RuntimeEndpoint(root, socket.normalize()))
        }
    }
}

sealed interface RuntimeEndpointResolution {
    data class Resolved(
        val endpoint: RuntimeEndpoint,
    ) : RuntimeEndpointResolution

    data class Rejected(
        val failure: RuntimeEndpointFailure,
    ) : RuntimeEndpointResolution
}

enum class RuntimeEndpointFailure {
    ROOT_MISMATCH,
    INVALID_SOCKET_PATH,
}

fun interface RuntimeEndpointLocator {
    /**
     * Proof transition: `CanonicalRoot -> RuntimeEndpointResolution`.
     *
     * Establishes the sole UDS endpoint derived from that exact root.
     * [RuntimeEndpointFailure] is the closed expected failure.
     */
    fun locate(root: CanonicalRoot): RuntimeEndpointResolution
}

/** Deterministically derives a bounded UDS name from the canonical root. */
class Sha256RuntimeEndpointLocator(
    private val socketDirectory: Path,
) : RuntimeEndpointLocator {
    override fun locate(root: CanonicalRoot): RuntimeEndpointResolution {
        val digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(root.path.toString().toByteArray(StandardCharsets.UTF_8)),
            0,
            12,
        )
        return RuntimeEndpoint.at(root, socketDirectory.resolve("kast-$digest.sock"))
    }
}

enum class IndexerExecutableFailure {
    NOT_ABSOLUTE,
    NOT_REGULAR,
    NOT_EXECUTABLE,
}

/** A regular executable admitted as the sole runtime process artifact. */
class IndexerExecutable private constructor(
    internal val path: Path,
) {
    companion object {
        /**
         * Proof transition: `Path -> Refinement<IndexerExecutable, IndexerExecutableFailure>`.
         *
         * Establishes one absolute, regular, executable process artifact.
         * [IndexerExecutableFailure] is the closed expected failure. The path may be extracted
         * only by [ExactRootProcessRuntimeDemander].
         */
        fun admit(path: Path): Refinement<IndexerExecutable, IndexerExecutableFailure> {
            if (!path.isAbsolute) {
                return Refinement.Rejected(IndexerExecutableFailure.NOT_ABSOLUTE)
            }
            if (Files.isSymbolicLink(path)) {
                return Refinement.Rejected(IndexerExecutableFailure.NOT_REGULAR)
            }
            val canonical = try {
                path.toRealPath()
            } catch (_: IOException) {
                return Refinement.Rejected(IndexerExecutableFailure.NOT_REGULAR)
            } catch (_: SecurityException) {
                return Refinement.Rejected(IndexerExecutableFailure.NOT_REGULAR)
            }
            return when {
                !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS) ->
                    Refinement.Rejected(IndexerExecutableFailure.NOT_REGULAR)
                !Files.isExecutable(canonical) ->
                    Refinement.Rejected(IndexerExecutableFailure.NOT_EXECUTABLE)
                else -> Refinement.Refined(IndexerExecutable(canonical))
            }
        }
    }
}

/** Exact process command derived only from admitted executable, root, and endpoint values. */
class IndexerLaunchCommand private constructor(
    internal val arguments: List<String>,
) {
    companion object {
        /**
         * Proof transition: `IndexerExecutable + CanonicalRoot + RuntimeEndpoint ->
         * IndexerLaunchCommand`.
         *
         * Establishes the installed indexer's exact root and UDS launch arguments. Construction
         * fails closed if the endpoint belongs to another root. Raw arguments may be extracted
         * only by [JdkRuntimeProcessStarter].
         */
        fun create(
            executable: IndexerExecutable,
            root: CanonicalRoot,
            endpoint: RuntimeEndpoint,
        ): IndexerLaunchCommandConstruction = if (endpoint.root == root) {
            IndexerLaunchCommandConstruction.Created(
                IndexerLaunchCommand(
                    listOf(
                        executable.path.toString(),
                        "--workspace-root=${root.path}",
                        "--socket-path=${endpoint.socketPath}",
                    ),
                ),
            )
        } else {
            IndexerLaunchCommandConstruction.Rejected(RuntimeEndpointFailure.ROOT_MISMATCH)
        }
    }
}

sealed interface IndexerLaunchCommandConstruction {
    data class Created(
        val command: IndexerLaunchCommand,
    ) : IndexerLaunchCommandConstruction

    data class Rejected(
        val failure: RuntimeEndpointFailure,
    ) : IndexerLaunchCommandConstruction
}

fun interface RuntimeProcessStarter {
    /** Executes only an already admitted [IndexerLaunchCommand]. */
    fun start(command: IndexerLaunchCommand): RuntimeProcessStart
}

sealed interface RuntimeProcessStart {
    data object Started : RuntimeProcessStart

    data object Rejected : RuntimeProcessStart
}

/** Sole process-effect adapter for an admitted indexer launch command. */
object JdkRuntimeProcessStarter : RuntimeProcessStarter {
    override fun start(command: IndexerLaunchCommand): RuntimeProcessStart = try {
        ProcessBuilder(command.arguments).inheritIO().start()
        RuntimeProcessStart.Started
    } catch (_: IOException) {
        RuntimeProcessStart.Rejected
    } catch (_: SecurityException) {
        RuntimeProcessStart.Rejected
    }
}

fun interface RuntimeEndpointProbe {
    /** Returns the closed reachability state observed through a native UDS connection attempt. */
    fun probe(endpoint: RuntimeEndpoint): RuntimeEndpointReachability
}

sealed interface RuntimeEndpointReachability {
    data object Reachable : RuntimeEndpointReachability

    data object Unreachable : RuntimeEndpointReachability
}

/** Proves endpoint reachability by completing a native UDS connection. */
object JdkUnixDomainEndpointProbe : RuntimeEndpointProbe {
    override fun probe(endpoint: RuntimeEndpoint): RuntimeEndpointReachability {
        val channel = try {
            SocketChannel.open(StandardProtocolFamily.UNIX)
        } catch (_: IOException) {
            return RuntimeEndpointReachability.Unreachable
        } catch (_: UnsupportedOperationException) {
            return RuntimeEndpointReachability.Unreachable
        }
        return channel.use { socket ->
            try {
                socket.connect(UnixDomainSocketAddress.of(endpoint.socketPath))
                RuntimeEndpointReachability.Reachable
            } catch (_: IOException) {
                RuntimeEndpointReachability.Unreachable
            } catch (_: SecurityException) {
                RuntimeEndpointReachability.Unreachable
            }
        }
    }
}

sealed interface RuntimeAdmission {
    data class Ready(
        val endpoint: RuntimeEndpoint,
    ) : RuntimeAdmission

    data class Rejected(
        val failure: RuntimeAdmissionFailure,
    ) : RuntimeAdmission
}

enum class RuntimeAdmissionFailure {
    PROCESS_START_FAILED,
    ENDPOINT_UNAVAILABLE,
    INTERRUPTED,
}

fun interface RuntimeDemander {
    /**
     * Proof transition: `CanonicalRoot + RuntimeEndpoint -> RuntimeAdmission`.
     *
     * Establishes that the runtime for the exact root is reachable at the requested endpoint.
     * [RuntimeAdmissionFailure] is the closed expected failure.
     */
    fun demand(root: CanonicalRoot, endpoint: RuntimeEndpoint): RuntimeAdmission
}

/** Starts only the admitted indexer artifact with explicit exact-root and socket arguments. */
class ExactRootProcessRuntimeDemander(
    private val executable: IndexerExecutable,
    private val processStarter: RuntimeProcessStarter = JdkRuntimeProcessStarter,
    private val endpointProbe: RuntimeEndpointProbe = JdkUnixDomainEndpointProbe,
) : RuntimeDemander {
    override fun demand(root: CanonicalRoot, endpoint: RuntimeEndpoint): RuntimeAdmission {
        if (endpoint.root != root) {
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ENDPOINT_UNAVAILABLE)
        }
        if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
            return RuntimeAdmission.Ready(endpoint)
        }
        val command = when (
            val construction = IndexerLaunchCommand.create(executable, root, endpoint)
        ) {
            is IndexerLaunchCommandConstruction.Created -> construction.command
            is IndexerLaunchCommandConstruction.Rejected ->
                return RuntimeAdmission.Rejected(
                    RuntimeAdmissionFailure.ENDPOINT_UNAVAILABLE,
                )
        }
        if (processStarter.start(command) is RuntimeProcessStart.Rejected) {
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.PROCESS_START_FAILED)
        }
        repeat(600) {
            if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
                return RuntimeAdmission.Ready(endpoint)
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.INTERRUPTED)
            }
        }
        return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ENDPOINT_UNAVAILABLE)
    }
}
