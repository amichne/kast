package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.managed.RuntimeStoreFailure
import io.github.amichne.kast.distribution.managed.SemanticRuntimeResolution
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** An exact-root UDS endpoint. */
class RuntimeEndpoint private constructor(
    val root: CanonicalRoot,
    val runtimeId: SemanticRuntimeId,
    internal val socketPath: Path,
) {
    override fun equals(other: Any?): Boolean =
        other is RuntimeEndpoint &&
        root == other.root && runtimeId == other.runtimeId && socketPath == other.socketPath

    override fun hashCode(): Int =
        31 * (31 * root.hashCode() + runtimeId.hashCode()) + socketPath.hashCode()

    companion object {
        /**
         * Proof transition: `CanonicalRoot + SemanticRuntimeId + Path ->
         * RuntimeEndpointResolution`.
         *
         * Establishes an absolute normalized socket endpoint bound to the exact canonical root.
         * [RuntimeEndpointFailure] is the closed expected failure. The raw socket path may be
         * extracted only by the process and UDS adapters.
         */
        fun at(
            root: CanonicalRoot,
            runtimeId: SemanticRuntimeId,
            socket: Path,
        ): RuntimeEndpointResolution {
            if (!socket.isAbsolute) {
                return RuntimeEndpointResolution.Rejected(
                    RuntimeEndpointFailure.INVALID_SOCKET_PATH,
                )
            }
            return RuntimeEndpointResolution.Resolved(
                RuntimeEndpoint(root, runtimeId, socket.normalize()),
            )
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
    private val runtimeId: SemanticRuntimeId,
) : RuntimeEndpointLocator {
    override fun locate(root: CanonicalRoot): RuntimeEndpointResolution {
        val digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(
                    "${root.path}\n${runtimeId.value}".toByteArray(StandardCharsets.UTF_8),
                ),
            0,
            12,
        )
        return RuntimeEndpoint.at(
            root,
            runtimeId,
            socketDirectory.resolve("kast-$digest.sock"),
        )
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
    internal val processSession: MacOsRuntimeProcessSession,
) {
    companion object {
        /**
         * Proof transition: `IndexerExecutable + CanonicalRoot + RuntimeEndpoint ->
         * IndexerLaunchCommand`.
         *
         * Establishes the installed indexer's exact root and UDS launch arguments. Construction
         * fails closed if the endpoint belongs to another root. Raw arguments may be extracted
         * only by [MacOsRuntimeProcessSession].
         */
        fun create(
            executable: IndexerExecutable,
            root: CanonicalRoot,
            endpoint: RuntimeEndpoint,
        ): IndexerLaunchCommandConstruction = if (endpoint.root == root) {
            IndexerLaunchCommandConstruction.Created(
                IndexerLaunchCommand(
                    arguments = listOf(
                        executable.path.toString(),
                        "--workspace-root=${root.path}",
                        "--socket-path=${endpoint.socketPath}",
                        "--runtime-id=${endpoint.runtimeId.value}",
                    ),
                    processSession = MacOsRuntimeProcessSession.from(endpoint),
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
    MANIFEST_INVALID,
    SOURCE_INVALID,
    ARTIFACT_UNAVAILABLE,
    DIGEST_MISMATCH,
    ARCHIVE_REJECTED,
    LAYOUT_INVALID,
    RUNTIME_INCOMPATIBLE,
    PROCESS_START_FAILED,
    SESSION_ENDED_BEFORE_READY,
    PROCESS_OBSERVATION_FAILED,
    ENDPOINT_UNAVAILABLE,
    RUNTIME_IDENTITY_MISMATCH,
    IDE_ROOT_INVALID,
    IDE_LOCATION_REJECTED,
    IDE_DESCRIPTOR_READ_REJECTED,
    IDE_DESCRIPTOR_REJECTED,
    IDE_ROOT_MISMATCH,
    IDE_SOCKET_MISMATCH,
    IDE_PROCESS_UNAVAILABLE,
    IDE_PROCESS_OBSERVATION_REJECTED,
    IDE_ENDPOINT_UNREACHABLE,
    IDE_CAPABILITY_UNAVAILABLE,
    IDE_VARIANT_UNAVAILABLE,
    INTERRUPTED,
}

fun interface RuntimeDemander {
    /**
     * Proof transition: `CanonicalRoot + RuntimeEndpoint -> RuntimeAdmission`.
     *
     * Establishes that the runtime for the exact root is reachable at the requested endpoint.
     * [RuntimeAdmissionFailure] is the closed expected failure.
     */
    fun demand(
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission
}

private enum class RuntimeStartupBound(
    val probeAttempts: Int,
) {
    ENTERPRISE_ACCEPTED(probeAttempts = 2_400),
}

private const val RUNTIME_PROBE_INTERVAL_MILLIS = 100L

/** Starts only the admitted indexer artifact with explicit exact-root and socket arguments. */
internal class ExactRootProcessRuntimeDemander(
    private val executable: IndexerExecutable,
    private val processStarter: RuntimeProcessStarter = JdkRuntimeProcessStarter,
    private val endpointProbe: RuntimeEndpointProbe = JdkUnixDomainEndpointProbe,
) : RuntimeDemander {
    override fun demand(
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission {
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
        val session = when (val start = processStarter.start(command)) {
            is RuntimeProcessStart.Accepted -> start.session
            RuntimeProcessStart.Interrupted -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.INTERRUPTED,
            )
            RuntimeProcessStart.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.PROCESS_START_FAILED,
            )
        }
        repeat(RuntimeStartupBound.ENTERPRISE_ACCEPTED.probeAttempts) {
            if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
                return RuntimeAdmission.Ready(endpoint)
            }
            when (session.observe()) {
                LaunchdServiceObservation.Present -> Unit
                LaunchdServiceObservation.Absent -> return RuntimeAdmission.Rejected(
                    RuntimeAdmissionFailure.SESSION_ENDED_BEFORE_READY,
                )
                LaunchdServiceObservation.Rejected -> return RuntimeAdmission.Rejected(
                    RuntimeAdmissionFailure.PROCESS_OBSERVATION_FAILED,
                )
                LaunchdServiceObservation.Interrupted -> return RuntimeAdmission.Rejected(
                    RuntimeAdmissionFailure.INTERRUPTED,
                )
            }
            try {
                Thread.sleep(RUNTIME_PROBE_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.INTERRUPTED)
            }
        }
        return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ENDPOINT_UNAVAILABLE)
    }
}

/** Realizes the exact manifest runtime before delegating exact-root process admission. */
fun interface InstalledSemanticRuntimeResolver {
    /**
     * Proof transition: `SemanticRuntimeManifest -> SemanticRuntimeResolution`.
     *
     * Establishes one verified installed runtime carrying the manifest identity, or returns the
     * closed [RuntimeStoreFailure] represented by [SemanticRuntimeResolution.Rejected]. Raw paths
     * may leave the installed capability only at process launch.
     */
    fun resolve(manifest: SemanticRuntimeManifest): SemanticRuntimeResolution
}

class ManagedExactRootRuntimeDemander(
    private val manifest: SemanticRuntimeManifest,
    private val resolver: InstalledSemanticRuntimeResolver,
) : RuntimeDemander {
    override fun demand(
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission {
        if (endpoint.runtimeId != manifest.runtimeId) {
            return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.RUNTIME_IDENTITY_MISMATCH,
            )
        }
        val installed = when (val resolution = resolver.resolve(manifest)) {
            is SemanticRuntimeResolution.Installed -> resolution.runtime
            is SemanticRuntimeResolution.Rejected -> return RuntimeAdmission.Rejected(
                resolution.failure.toAdmissionFailure(),
            )
        }
        val executable = when (val admitted = IndexerExecutable.admit(installed.executable)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.LAYOUT_INVALID,
            )
        }
        return ExactRootProcessRuntimeDemander(executable).demand(root, endpoint)
    }
}

private fun RuntimeStoreFailure.toAdmissionFailure(): RuntimeAdmissionFailure = when (this) {
    RuntimeStoreFailure.STORE_INVALID -> RuntimeAdmissionFailure.SOURCE_INVALID
    RuntimeStoreFailure.ARTIFACT_UNAVAILABLE -> RuntimeAdmissionFailure.ARTIFACT_UNAVAILABLE
    RuntimeStoreFailure.DIGEST_MISMATCH -> RuntimeAdmissionFailure.DIGEST_MISMATCH
    RuntimeStoreFailure.ARCHIVE_REJECTED -> RuntimeAdmissionFailure.ARCHIVE_REJECTED
    RuntimeStoreFailure.LAYOUT_INVALID -> RuntimeAdmissionFailure.LAYOUT_INVALID
    RuntimeStoreFailure.RUNTIME_INCOMPATIBLE -> RuntimeAdmissionFailure.RUNTIME_INCOMPATIBLE
    RuntimeStoreFailure.INTERRUPTED -> RuntimeAdmissionFailure.INTERRUPTED
}
