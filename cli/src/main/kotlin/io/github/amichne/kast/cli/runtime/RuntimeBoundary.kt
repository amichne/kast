package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.managed.RuntimeStoreFailure
import io.github.amichne.kast.distribution.managed.SemanticRuntimeResolution
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorFailure
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
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
    LAUNCH_CONTEXT_REQUIRED,
    STARTUP_LOG_INVALID,
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

/** A deterministic physical directory that leaves enough bytes for every derived UDS name. */
internal class RuntimeSocketDirectory private constructor(
    internal val path: Path,
) {
    companion object {
        internal const val MAXIMUM_ENDPOINT_PATH_BYTES = 103

        /**
         * Proof transition: `InstalledRuntimeDirectory -> RuntimeSocketDirectory`.
         *
         * Maps an admitted logical runtime namespace into one fixed-length physical namespace.
         * The complete endpoint shape is checked against macOS's Unix-domain address bound before
         * this proof-carrying directory can reach a locator. Raw paths leave only at endpoint
         * derivation and filesystem boundaries.
         */
        internal fun from(logicalDirectory: InstalledRuntimeDirectory): RuntimeSocketDirectory {
            val namespace = sha256Prefix(logicalDirectory.path.toString(), DIGEST_BYTES)
            val physical = PHYSICAL_SOCKET_ROOT.resolve("kast-runtime-$namespace")
            val maximumEndpoint = physical.resolve(
                "kast-${"0".repeat(ENDPOINT_TOKEN_CHARACTERS)}.sock",
            )
            check(
                maximumEndpoint.toString().toByteArray(StandardCharsets.UTF_8).size <=
                    MAXIMUM_ENDPOINT_PATH_BYTES,
            )
            return RuntimeSocketDirectory(physical)
        }

        private val PHYSICAL_SOCKET_ROOT = Path.of("/tmp")
        private const val DIGEST_BYTES = 12
        private const val ENDPOINT_TOKEN_CHARACTERS = 43
    }
}

/** Deterministically derives a bounded UDS name from the canonical root. */
internal class Sha256RuntimeEndpointLocator(
    private val socketDirectory: RuntimeSocketDirectory,
    private val runtimeId: SemanticRuntimeId,
) : RuntimeEndpointLocator {
    override fun locate(root: CanonicalRoot): RuntimeEndpointResolution {
        val digest = sha256Prefix(
            "${root.path}\n${runtimeId.value}",
            ENDPOINT_DIGEST_BYTES,
        )
        return RuntimeEndpoint.at(
            root,
            runtimeId,
            socketDirectory.path.resolve("kast-$digest.sock"),
        )
    }

    private companion object {
        const val ENDPOINT_DIGEST_BYTES = 12
    }
}

/** Refines a semantic endpoint to one exact installed-IDE, JBR, and payload cache identity. */
internal fun RuntimeEndpoint.forSidecarCache(
    cacheIdentity: String,
    semanticRuntimeId: SemanticRuntimeId,
): RuntimeEndpointResolution {
    if (!Regex("sha256:[0-9a-f]{64}").matches(cacheIdentity)) {
        return RuntimeEndpointResolution.Rejected(RuntimeEndpointFailure.INVALID_SOCKET_PATH)
    }
    val socketDirectory = socketPath.parent
        ?: return RuntimeEndpointResolution.Rejected(RuntimeEndpointFailure.INVALID_SOCKET_PATH)
    val exactToken = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(
            "$cacheIdentity\n${semanticRuntimeId.value}".toByteArray(StandardCharsets.UTF_8),
        ),
    )
    return RuntimeEndpoint.at(
        root,
        semanticRuntimeId,
        socketDirectory.resolve(
            "kast-$exactToken.sock",
        ),
    )
}

private fun sha256Prefix(value: String, bytes: Int): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    0,
    bytes,
)

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
    internal val runtime: InstalledIdeRuntime,
    internal val startupLog: Path,
    internal val processSession: MacOsRuntimeProcessSession,
) {
    companion object {
        /**
         * Proof transition: `IndexerExecutable + CanonicalRoot + RuntimeEndpoint ->
         * IndexerLaunchCommand`.
         *
         * Establishes the installed indexer's exact root and UDS launch arguments. Construction
         * fails closed if the endpoint belongs to another root. Raw arguments may be extracted
         * only by an admitted [RuntimeProcessStarter].
         */
        fun create(
            executable: IndexerExecutable,
            root: CanonicalRoot,
            endpoint: RuntimeEndpoint,
            context: SidecarLaunchContext,
        ): IndexerLaunchCommandConstruction = if (
            endpoint.root == root &&
            !Files.isSymbolicLink(context.logDirectory.resolve("startup.log"))
        ) {
            IndexerLaunchCommandConstruction.Created(
                IndexerLaunchCommand(
                    arguments = listOf(
                        executable.path.toString(),
                        "--workspace-root=${root.path}",
                        "--socket-path=${endpoint.socketPath}",
                        "--runtime-id=${endpoint.runtimeId.value}",
                        "--idea-home=${context.runtime.home}",
                        "--java-executable=${context.runtime.javaExecutable}",
                        "--idea-system-path=${context.systemDirectory}",
                        "--idea-config-path=${context.configDirectory}",
                        "--idea-log-path=${context.logDirectory}",
                        "--private-plugins-path=${context.privatePluginsDirectory}",
                        "--cache-state-path=${context.cacheRoot.resolve("cache-state")}",
                    ),
                    runtime = context.runtime,
                    startupLog = context.logDirectory.resolve("startup.log"),
                    processSession = MacOsRuntimeProcessSession.from(endpoint),
                ),
            )
        } else {
            IndexerLaunchCommandConstruction.Rejected(
                if (endpoint.root != root) {
                    RuntimeEndpointFailure.ROOT_MISMATCH
                } else {
                    RuntimeEndpointFailure.STARTUP_LOG_INVALID
                },
            )
        }

        /** Legacy callers cannot manufacture a launch without the new sidecar authority. */
        fun create(
            executable: IndexerExecutable,
            root: CanonicalRoot,
            endpoint: RuntimeEndpoint,
        ): IndexerLaunchCommandConstruction = IndexerLaunchCommandConstruction.Rejected(
            RuntimeEndpointFailure.LAUNCH_CONTEXT_REQUIRED,
        )
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

sealed interface RuntimeAdmissionFailure {
    data object ManifestInvalid : RuntimeAdmissionFailure
    data object SourceInvalid : RuntimeAdmissionFailure
    data object ArtifactUnavailable : RuntimeAdmissionFailure
    data object DigestMismatch : RuntimeAdmissionFailure
    data object ArchiveRejected : RuntimeAdmissionFailure
    data object LayoutInvalid : RuntimeAdmissionFailure
    data object RuntimeIncompatible : RuntimeAdmissionFailure
    data class ProcessStartFailed(
        val failure: RuntimeProcessStartFailure,
    ) : RuntimeAdmissionFailure
    data object SessionEndedBeforeReady : RuntimeAdmissionFailure
    data object ProcessObservationFailed : RuntimeAdmissionFailure
    data object EndpointUnavailable : RuntimeAdmissionFailure
    data object RuntimeIdentityMismatch : RuntimeAdmissionFailure
    data object LegacySidecarActive : RuntimeAdmissionFailure
    data object IdeRootInvalid : RuntimeAdmissionFailure
    data object IdeLocationRejected : RuntimeAdmissionFailure
    data object IdeDescriptorReadRejected : RuntimeAdmissionFailure
    data class IdeDescriptorRejected(
        val failure: IdeEndpointDescriptorFailure,
    ) : RuntimeAdmissionFailure
    data object IdeRootMismatch : RuntimeAdmissionFailure
    data object IdeSocketMismatch : RuntimeAdmissionFailure
    data object IdeProcessUnavailable : RuntimeAdmissionFailure
    data object IdeProcessObservationRejected : RuntimeAdmissionFailure
    data object IdeEndpointUnreachable : RuntimeAdmissionFailure
    data object IdeCapabilityUnavailable : RuntimeAdmissionFailure
    data object IdeVariantUnavailable : RuntimeAdmissionFailure
    data object Interrupted : RuntimeAdmissionFailure
    data class InstalledIdeRejected(val failure: IndexSeedFailure) : RuntimeAdmissionFailure
    data class SidecarCacheRejected(val failure: SidecarCacheFailure) : RuntimeAdmissionFailure
}

internal fun RuntimeAdmissionFailure.outputReason(): String = when (this) {
    RuntimeAdmissionFailure.ManifestInvalid -> "manifest-invalid"
    RuntimeAdmissionFailure.SourceInvalid -> "source-invalid"
    RuntimeAdmissionFailure.ArtifactUnavailable -> "artifact-unavailable"
    RuntimeAdmissionFailure.DigestMismatch -> "digest-mismatch"
    RuntimeAdmissionFailure.ArchiveRejected -> "archive-rejected"
    RuntimeAdmissionFailure.LayoutInvalid -> "layout-invalid"
    RuntimeAdmissionFailure.RuntimeIncompatible -> "runtime-incompatible"
    is RuntimeAdmissionFailure.ProcessStartFailed -> when (failure) {
        RuntimeProcessStartFailure.IdeaJbrUnavailable -> "idea-jbr-unavailable"
        RuntimeProcessStartFailure.UserHomeUnavailable -> "user-home-unavailable"
        RuntimeProcessStartFailure.SessionObservationRejected,
        RuntimeProcessStartFailure.SessionSubmissionRejected,
        RuntimeProcessStartFailure.ProcessCreationRejected,
        RuntimeProcessStartFailure.ChildStartRejected,
            -> "process-start-failed"
    }
    RuntimeAdmissionFailure.SessionEndedBeforeReady -> "session-ended-before-ready"
    RuntimeAdmissionFailure.ProcessObservationFailed -> "process-observation-failed"
    RuntimeAdmissionFailure.EndpointUnavailable -> "endpoint-unavailable"
    RuntimeAdmissionFailure.RuntimeIdentityMismatch -> "runtime-identity-mismatch"
    RuntimeAdmissionFailure.LegacySidecarActive -> "legacy-sidecar-active"
    RuntimeAdmissionFailure.IdeRootInvalid -> "ide-root-invalid"
    RuntimeAdmissionFailure.IdeLocationRejected -> "ide-location-rejected"
    RuntimeAdmissionFailure.IdeDescriptorReadRejected -> "ide-descriptor-read-rejected"
    is RuntimeAdmissionFailure.IdeDescriptorRejected -> "ide-descriptor-rejected"
    RuntimeAdmissionFailure.IdeRootMismatch -> "ide-root-mismatch"
    RuntimeAdmissionFailure.IdeSocketMismatch -> "ide-socket-mismatch"
    RuntimeAdmissionFailure.IdeProcessUnavailable -> "ide-process-unavailable"
    RuntimeAdmissionFailure.IdeProcessObservationRejected ->
        "ide-process-observation-rejected"
    RuntimeAdmissionFailure.IdeEndpointUnreachable -> "ide-endpoint-unreachable"
    RuntimeAdmissionFailure.IdeCapabilityUnavailable -> "ide-capability-unavailable"
    RuntimeAdmissionFailure.IdeVariantUnavailable -> "ide-variant-unavailable"
    RuntimeAdmissionFailure.Interrupted -> "interrupted"
    is RuntimeAdmissionFailure.InstalledIdeRejected -> when (failure) {
        IndexSeedFailure.Ambiguity -> "idea-installation-ambiguous"
        IndexSeedFailure.MissingInstallation -> "idea-installation-missing"
        is IndexSeedFailure.Incompatibility -> "idea-installation-incompatible"
        else -> "idea-installation-rejected"
    }
    is RuntimeAdmissionFailure.SidecarCacheRejected -> when (failure) {
        SidecarCacheFailure.FilesystemRejected -> "sidecar-cache-rejected"
        SidecarCacheFailure.RebuildRequired -> "sidecar-cache-rebuild-required"
        is SidecarCacheFailure.SeedRejected -> when (failure.failure) {
            IndexSeedFailure.RunningSourceIde -> "index-seed-source-running"
            IndexSeedFailure.ConsentAbsent -> "index-seed-consent-absent"
            IndexSeedFailure.UnsupportedFilesystem -> "index-seed-filesystem-unsupported"
            IndexSeedFailure.SourceMutation -> "index-seed-source-mutated"
            IndexSeedFailure.CopyFailure -> "index-seed-copy-failed"
            else -> "index-seed-rejected"
        }
    }
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
    private val launchContext: SidecarLaunchContext,
    private val processStarter: RuntimeProcessStarter = JdkRuntimeProcessStarter,
    private val endpointProbe: RuntimeEndpointProbe = JdkUnixDomainEndpointProbe,
) : RuntimeDemander {
    override fun demand(
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission {
        if (endpoint.root != root) {
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
        }
        if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
            return RuntimeAdmission.Ready(endpoint)
        }
        val command = when (
            val construction = IndexerLaunchCommand.create(
                executable,
                root,
                endpoint,
                launchContext,
            )
        ) {
            is IndexerLaunchCommandConstruction.Created -> construction.command
            is IndexerLaunchCommandConstruction.Rejected ->
                return RuntimeAdmission.Rejected(
                    RuntimeAdmissionFailure.EndpointUnavailable,
                )
        }
        val session = when (val start = processStarter.start(command)) {
            is RuntimeProcessStart.Accepted -> start.session
            RuntimeProcessStart.Interrupted -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.Interrupted,
            )
            is RuntimeProcessStart.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.ProcessStartFailed(start.failure),
            )
        }
        repeat(RuntimeStartupBound.ENTERPRISE_ACCEPTED.probeAttempts) {
            if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
                return RuntimeAdmission.Ready(endpoint)
            }
            when (session.observe()) {
                RuntimeSessionObservation.Present -> Unit
                RuntimeSessionObservation.Absent -> return RuntimeAdmission.Rejected(
                    RuntimeAdmissionFailure.SessionEndedBeforeReady,
                )
                RuntimeSessionObservation.Rejected -> return RuntimeAdmission.Rejected(
                    RuntimeAdmissionFailure.ProcessObservationFailed,
                )
                RuntimeSessionObservation.Interrupted -> return RuntimeAdmission.Rejected(
                    RuntimeAdmissionFailure.Interrupted,
                )
            }
            try {
                Thread.sleep(RUNTIME_PROBE_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.Interrupted)
            }
        }
        return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
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
    private val launchContext: SidecarLaunchContext? = null,
) : RuntimeDemander {
    override fun demand(
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission {
        if (endpoint.runtimeId != manifest.runtimeId) {
            return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.RuntimeIdentityMismatch,
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
                RuntimeAdmissionFailure.LayoutInvalid,
            )
        }
        val context = launchContext ?: return RuntimeAdmission.Rejected(
            RuntimeAdmissionFailure.LayoutInvalid,
        )
        return ExactRootProcessRuntimeDemander(executable, context).demand(root, endpoint)
    }
}

private fun RuntimeStoreFailure.toAdmissionFailure(): RuntimeAdmissionFailure = when (this) {
    RuntimeStoreFailure.STORE_INVALID -> RuntimeAdmissionFailure.SourceInvalid
    RuntimeStoreFailure.ARTIFACT_UNAVAILABLE -> RuntimeAdmissionFailure.ArtifactUnavailable
    RuntimeStoreFailure.DIGEST_MISMATCH -> RuntimeAdmissionFailure.DigestMismatch
    RuntimeStoreFailure.ARCHIVE_REJECTED -> RuntimeAdmissionFailure.ArchiveRejected
    RuntimeStoreFailure.LAYOUT_INVALID -> RuntimeAdmissionFailure.LayoutInvalid
    RuntimeStoreFailure.RUNTIME_INCOMPATIBLE -> RuntimeAdmissionFailure.RuntimeIncompatible
    RuntimeStoreFailure.INTERRUPTED -> RuntimeAdmissionFailure.Interrupted
}
