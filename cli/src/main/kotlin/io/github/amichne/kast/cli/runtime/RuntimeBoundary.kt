package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.runtime.bootstrap.SidecarBootstrapAttemptLock
import io.github.amichne.kast.cli.runtime.bootstrap.SidecarBootstrapAttemptLockExecution
import io.github.amichne.kast.cli.runtime.bootstrap.SidecarBootstrapStateFile
import io.github.amichne.kast.cli.runtime.bootstrap.SidecarBootstrapStateFileFailure
import io.github.amichne.kast.cli.runtime.bootstrap.SidecarBootstrapStateObservation
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.contract.bootstrap.SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapDocumentFailure
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentFailure
import io.github.amichne.kast.distribution.managed.RuntimeStoreFailure
import io.github.amichne.kast.distribution.managed.SemanticRuntimeResolution
import io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliasReceipt
import io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliases
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** An exact-root UDS endpoint, retaining its physical location and optional alias receipt. */
class RuntimeEndpoint
private constructor(
    val root: CanonicalRoot,
    val runtimeId: SemanticRuntimeId,
    internal val socketPath: Path,
    internal val physicalSocketPath: Path,
    internal val route: RuntimeSocketRoute,
) {
    override fun equals(other: Any?): Boolean =
        other is RuntimeEndpoint &&
            root == other.root &&
            runtimeId == other.runtimeId &&
            socketPath == other.socketPath &&
            physicalSocketPath == other.physicalSocketPath

    override fun hashCode(): Int = 31 * (31 * root.hashCode() + runtimeId.hashCode()) + socketPath.hashCode()

    internal fun prepareTransport(): RuntimeEndpointResolution = PosixRuntimeEndpointArtifacts.prepareTransport(this)

    internal fun observeTransport(): RuntimeEndpointResolution = PosixRuntimeEndpointArtifacts.observeTransport(this)

    internal fun canonicalTransport(): RuntimeEndpointResolution =
        if (socketPath != physicalSocketPath) rejected()
        else
            RuntimeEndpointResolution.Resolved(
                RuntimeEndpoint(root, runtimeId, socketPath, physicalSocketPath, RuntimeSocketRoute.Canonical)
            )

    internal fun admittedAlias(receipt: InstalledEndpointAliasReceipt): RuntimeEndpointResolution =
        if (receipt.physicalDirectory != physicalSocketPath.parent || receipt.alias != socketPath.parent) rejected()
        else
            RuntimeEndpointResolution.Resolved(
                RuntimeEndpoint(
                    root,
                    runtimeId,
                    socketPath,
                    physicalSocketPath,
                    RuntimeSocketRoute.Aliased(receipt),
                )
            )

    internal fun withFileName(name: String, runtime: SemanticRuntimeId): RuntimeEndpointResolution =
        RuntimeEndpointResolution.Resolved(
            RuntimeEndpoint(
                root,
                runtime,
                socketPath.resolveSibling(name),
                physicalSocketPath.resolveSibling(name),
                route,
            )
        )

    private fun rejected() = RuntimeEndpointResolution.Rejected(RuntimeEndpointFailure.INVALID_SOCKET_PATH)

    companion object {
        fun at(root: CanonicalRoot, runtimeId: SemanticRuntimeId, socket: Path): RuntimeEndpointResolution {
            if (!socket.isAbsolute || socket.normalize() != socket)
                return RuntimeEndpointResolution.Rejected(RuntimeEndpointFailure.INVALID_SOCKET_PATH)
            if (InstalledEndpointAliases.isReservedSocket(socket))
                return when (val admission = InstalledEndpointAliases.observe(socket)) {
                    is Validation.Validated ->
                        RuntimeEndpointResolution.Resolved(
                            RuntimeEndpoint(
                                root,
                                runtimeId,
                                socket,
                                admission.value.physicalDirectory.resolve(socket.fileName),
                                RuntimeSocketRoute.Aliased(admission.value),
                            )
                        )
                    is Validation.Rejected ->
                        RuntimeEndpointResolution.Rejected(RuntimeEndpointFailure.INVALID_SOCKET_PATH)
                }
            return RuntimeEndpointResolution.Resolved(
                RuntimeEndpoint(root, runtimeId, socket, socket, RuntimeSocketRoute.Canonical)
            )
        }

        internal fun planned(
            root: CanonicalRoot,
            runtimeId: SemanticRuntimeId,
            socket: Path,
            physical: Path,
        ): RuntimeEndpointResolution =
            RuntimeEndpointResolution.Resolved(
                RuntimeEndpoint(root, runtimeId, socket, physical, RuntimeSocketRoute.Planned)
            )
    }
}

internal sealed interface RuntimeSocketRoute {
    data object Canonical : RuntimeSocketRoute

    data object Planned : RuntimeSocketRoute

    data class Aliased(val receipt: InstalledEndpointAliasReceipt) : RuntimeSocketRoute
}

sealed interface RuntimeEndpointResolution {
    data class Resolved(val endpoint: RuntimeEndpoint) : RuntimeEndpointResolution

    data class Rejected(val failure: RuntimeEndpointFailure) : RuntimeEndpointResolution
}

enum class RuntimeEndpointFailure {
    ROOT_MISMATCH,
    INVALID_SOCKET_PATH,
    STARTUP_LOG_INVALID,
}

fun interface RuntimeEndpointLocator {
    /**
     * Proof transition: `CanonicalRoot -> RuntimeEndpointResolution`.
     *
     * Establishes the sole UDS endpoint derived from that exact root. [RuntimeEndpointFailure] is the closed expected
     * failure.
     */
    fun locate(root: CanonicalRoot): RuntimeEndpointResolution
}

/** The physical state directory is installation owned; a short alias is only a transport route. */
internal class RuntimeSocketDirectory
private constructor(
    internal val path: Path,
    internal val physicalPath: Path,
) {
    companion object {
        internal const val MAXIMUM_ENDPOINT_PATH_BYTES = CliOperationalLimits.maximumEndpointPathBytes

        internal fun from(logicalDirectory: InstalledRuntimeDirectory): RuntimeSocketDirectory =
            installed(logicalDirectory.path.resolve("state/run"))

        internal fun installed(physicalRun: Path): RuntimeSocketDirectory {
            val maximumEndpoint = physicalRun.resolve("kast-${"0".repeat(43)}.sock")
            val transport = InstalledEndpointAliases.transportPath(maximumEndpoint)
            check(transport.toString().toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_ENDPOINT_PATH_BYTES)
            return RuntimeSocketDirectory(transport.parent, physicalRun)
        }
    }
}

/** Deterministically derives a bounded UDS name from the canonical root. */
internal class Sha256RuntimeEndpointLocator(
    private val socketDirectory: RuntimeSocketDirectory,
    private val runtimeId: SemanticRuntimeId,
) : RuntimeEndpointLocator {
    override fun locate(root: CanonicalRoot): RuntimeEndpointResolution {
        val digest =
            sha256Prefix(
                "${root.path}\n${runtimeId.value}",
                ENDPOINT_DIGEST_BYTES,
            )
        return RuntimeEndpoint.planned(
            root,
            runtimeId,
            socketDirectory.path.resolve("kast-$digest.sock"),
            socketDirectory.physicalPath.resolve("kast-$digest.sock"),
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
    physicalCacheRoot: Path,
): RuntimeEndpointResolution {
    if (
        !Regex("sha256:[0-9a-f]{64}").matches(cacheIdentity) ||
            !physicalCacheRoot.isAbsolute ||
            physicalCacheRoot.normalize() != physicalCacheRoot
    ) {
        return RuntimeEndpointResolution.Rejected(RuntimeEndpointFailure.INVALID_SOCKET_PATH)
    }
    val exactToken =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        "$cacheIdentity\n${semanticRuntimeId.value}\n$physicalCacheRoot"
                            .toByteArray(StandardCharsets.UTF_8)
                    )
            )
    return withFileName("kast-$exactToken.sock", semanticRuntimeId)
}

private fun sha256Prefix(value: String, bytes: Int): String =
    HexFormat.of()
        .formatHex(
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
class IndexerExecutable private constructor(internal val path: Path) {
    companion object {
        /**
         * Proof transition: `Path -> Refinement<IndexerExecutable, IndexerExecutableFailure>`.
         *
         * Establishes one absolute, regular, executable process artifact. [IndexerExecutableFailure] is the closed
         * expected failure. The path may be extracted only by [ExactRootProcessRuntimeDemander].
         */
        fun admit(path: Path): Refinement<IndexerExecutable, IndexerExecutableFailure> {
            if (!path.isAbsolute) {
                return Refinement.Rejected(IndexerExecutableFailure.NOT_ABSOLUTE)
            }
            if (Files.isSymbolicLink(path)) {
                return Refinement.Rejected(IndexerExecutableFailure.NOT_REGULAR)
            }
            val canonical =
                try {
                    path.toRealPath()
                } catch (_: IOException) {
                    return Refinement.Rejected(IndexerExecutableFailure.NOT_REGULAR)
                } catch (_: SecurityException) {
                    return Refinement.Rejected(IndexerExecutableFailure.NOT_REGULAR)
                }
            return when {
                !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS) ->
                    Refinement.Rejected(IndexerExecutableFailure.NOT_REGULAR)
                !Files.isExecutable(canonical) -> Refinement.Rejected(IndexerExecutableFailure.NOT_EXECUTABLE)
                else -> Refinement.Refined(IndexerExecutable(canonical))
            }
        }
    }
}

/** Exact process command derived only from admitted executable, root, and endpoint values. */
class IndexerLaunchCommand
private constructor(
    internal val arguments: List<String>,
    internal val runtime: InstalledIdeRuntime,
    internal val importEnvironment: GradleImportEnvironment,
    internal val sidecarEnvironment: SidecarEnvironmentInputs,
    internal val startupLog: Path,
    internal val bootstrapState: Path,
    internal val bootstrapAttemptId: SemanticRuntimeBootstrapAttemptId,
    internal val processSession: MacOsRuntimeProcessSession,
) {
    companion object {
        /**
         * Proof transition: `IndexerExecutable + CanonicalRoot + RuntimeEndpoint -> IndexerLaunchCommand`.
         *
         * Establishes the installed indexer's exact root and UDS launch arguments. Construction fails closed if the
         * endpoint belongs to another root. Raw arguments may be extracted only by an admitted [RuntimeProcessStarter].
         */
        fun create(
            executable: IndexerExecutable,
            root: CanonicalRoot,
            endpoint: RuntimeEndpoint,
            context: SidecarLaunchContext,
            bootstrapAttemptId: SemanticRuntimeBootstrapAttemptId,
        ): IndexerLaunchCommandConstruction =
            if (endpoint.root == root && !Files.isSymbolicLink(context.logDirectory.resolve("startup.log"))) {
                IndexerLaunchCommandConstruction.Created(
                    IndexerLaunchCommand(
                        arguments =
                            listOf(
                                executable.path.toString(),
                                "--workspace-root=${root.path}",
                                "--socket-path=${endpoint.socketPath}",
                                "--runtime-id=${endpoint.runtimeId.value}",
                                "--idea-home=${context.runtime.home}",
                                "--java-executable=${context.runtime.javaExecutable}",
                                "--max-heap-mib=${context.maxHeap.mebibytes}",
                                "--idea-system-path=${context.systemDirectory}",
                                "--idea-config-path=${context.configDirectory}",
                                "--idea-log-path=${context.logDirectory}",
                                "--private-plugins-path=${context.privatePluginsDirectory}",
                                "--cache-state-path=${context.cacheRoot.resolve("cache-state")}",
                                "--bootstrap-state-path=${context.cacheRoot.resolve(SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME)}",
                                "--bootstrap-attempt-id=${bootstrapAttemptId.value}",
                            ),
                        runtime = context.runtime,
                        importEnvironment = context.importEnvironment,
                        sidecarEnvironment = context.sidecarEnvironment,
                        startupLog = context.logDirectory.resolve("startup.log"),
                        bootstrapState = context.cacheRoot.resolve(SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME),
                        bootstrapAttemptId = bootstrapAttemptId,
                        processSession = MacOsRuntimeProcessSession.from(endpoint),
                    )
                )
            } else {
                IndexerLaunchCommandConstruction.Rejected(
                    if (endpoint.root != root) {
                        RuntimeEndpointFailure.ROOT_MISMATCH
                    } else {
                        RuntimeEndpointFailure.STARTUP_LOG_INVALID
                    }
                )
            }
    }
}

sealed interface IndexerLaunchCommandConstruction {
    data class Created(val command: IndexerLaunchCommand) : IndexerLaunchCommandConstruction

    data class Rejected(val failure: RuntimeEndpointFailure) : IndexerLaunchCommandConstruction
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
        val admitted =
            when (val admission = endpoint.observeTransport()) {
                is RuntimeEndpointResolution.Resolved -> admission.endpoint
                is RuntimeEndpointResolution.Rejected -> return RuntimeEndpointReachability.Unreachable
            }
        val channel =
            try {
                SocketChannel.open(StandardProtocolFamily.UNIX)
            } catch (_: IOException) {
                return RuntimeEndpointReachability.Unreachable
            } catch (_: UnsupportedOperationException) {
                return RuntimeEndpointReachability.Unreachable
            }
        return channel.use { socket ->
            try {
                socket.connect(UnixDomainSocketAddress.of(admitted.socketPath))
                when (admitted.observeTransport()) {
                    is RuntimeEndpointResolution.Resolved -> RuntimeEndpointReachability.Reachable
                    is RuntimeEndpointResolution.Rejected -> RuntimeEndpointReachability.Unreachable
                }
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
        val removed: Set<RuntimeEndpointArtifact> = emptySet(),
        val workerBinding: io.github.amichne.kast.appserver.WorkerRouteBinding =
            io.github.amichne.kast.appserver.WorkerRouteBinding.EffectBoundary,
        val deadline: RuntimeInvocationDeadline = RuntimeInvocationDeadline.EffectBoundary,
        val wireAuthority: RuntimeWireAuthority = RuntimeWireAuthority.EffectBoundary,
    ) : RuntimeAdmission

    data class Rejected(val failure: RuntimeAdmissionFailure) : RuntimeAdmission
}

sealed interface RuntimeAdmissionFailure {
    data class WorkerControlRejected(val failure: io.github.amichne.kast.appserver.WorkerControlFailure) :
        RuntimeAdmissionFailure

    data class GradleImportEnvironmentRejected(val failure: GradleImportEnvironmentFailure) : RuntimeAdmissionFailure

    data object ManifestInvalid : RuntimeAdmissionFailure

    data object SourceInvalid : RuntimeAdmissionFailure

    data object ArtifactUnavailable : RuntimeAdmissionFailure

    data object DigestMismatch : RuntimeAdmissionFailure

    data object ArchiveRejected : RuntimeAdmissionFailure

    data object LayoutInvalid : RuntimeAdmissionFailure

    data object RuntimeIncompatible : RuntimeAdmissionFailure

    data class ProcessStartFailed(val failure: RuntimeProcessStartFailure) : RuntimeAdmissionFailure

    data object SessionEndedBeforeReady : RuntimeAdmissionFailure

    data object BootstrapStateUnavailable : RuntimeAdmissionFailure

    data object BootstrapAttemptUnavailable : RuntimeAdmissionFailure

    data object BootstrapAttemptMismatch : RuntimeAdmissionFailure

    data object BootstrapAttemptLockUnavailable : RuntimeAdmissionFailure

    data class BootstrapProtocolRejected(val failure: SemanticRuntimeBootstrapDocumentFailure) : RuntimeAdmissionFailure

    data class IntellijBootstrap(val state: SemanticRuntimeBootstrapState.Rejected) : RuntimeAdmissionFailure

    data object ProcessObservationFailed : RuntimeAdmissionFailure

    data object EndpointUnavailable : RuntimeAdmissionFailure

    data object RuntimeIdentityMismatch : RuntimeAdmissionFailure

    data object LegacySidecarActive : RuntimeAdmissionFailure

    data object Interrupted : RuntimeAdmissionFailure

    data class InstalledIdeRejected(val failure: IndexSeedFailure) : RuntimeAdmissionFailure

    data class SidecarCacheRejected(val failure: SidecarCacheFailure) : RuntimeAdmissionFailure

    data class StopRejected(val failure: RuntimeStopFailure) : RuntimeAdmissionFailure

    data class CacheQuarantineRejected(val rejection: RootSidecarCacheQuarantine.Rejected) : RuntimeAdmissionFailure
}

internal fun RuntimeAdmissionFailure.outputReason(): String =
    when (this) {
        is RuntimeAdmissionFailure.StopRejected -> "start-${failure.name.lowercase().replace('_', '-')}"
        is RuntimeAdmissionFailure.CacheQuarantineRejected ->
            "start-cache-${rejection.failure.name.lowercase().replace('_', '-')}"
        is RuntimeAdmissionFailure.GradleImportEnvironmentRejected ->
            "gradle-import-environment-${failure.name.lowercase().replace('_', '-')}"
        RuntimeAdmissionFailure.ManifestInvalid -> "manifest-invalid"
        RuntimeAdmissionFailure.SourceInvalid -> "source-invalid"
        RuntimeAdmissionFailure.ArtifactUnavailable -> "artifact-unavailable"
        RuntimeAdmissionFailure.DigestMismatch -> "digest-mismatch"
        RuntimeAdmissionFailure.ArchiveRejected -> "archive-rejected"
        RuntimeAdmissionFailure.LayoutInvalid -> "layout-invalid"
        RuntimeAdmissionFailure.RuntimeIncompatible -> "runtime-incompatible"
        is RuntimeAdmissionFailure.ProcessStartFailed ->
            when (failure) {
                RuntimeProcessStartFailure.GradleImportEnvironmentRejected -> "gradle-import-environment-rejected"
                RuntimeProcessStartFailure.IdeaJbrUnavailable -> "idea-jbr-unavailable"
                RuntimeProcessStartFailure.UserHomeUnavailable -> "user-home-unavailable"
                RuntimeProcessStartFailure.SessionObservationRejected,
                RuntimeProcessStartFailure.SessionSubmissionRejected,
                RuntimeProcessStartFailure.ProcessCreationRejected,
                RuntimeProcessStartFailure.ChildStartRejected -> "process-start-failed"
            }
        RuntimeAdmissionFailure.SessionEndedBeforeReady -> "session-ended-before-ready"
        RuntimeAdmissionFailure.BootstrapStateUnavailable -> "bootstrap-state-unavailable"
        RuntimeAdmissionFailure.BootstrapAttemptUnavailable -> "bootstrap-attempt-unavailable"
        RuntimeAdmissionFailure.BootstrapAttemptMismatch -> "bootstrap-attempt-mismatch"
        RuntimeAdmissionFailure.BootstrapAttemptLockUnavailable -> "bootstrap-attempt-lock-unavailable"
        is RuntimeAdmissionFailure.BootstrapProtocolRejected ->
            when (failure) {
                SemanticRuntimeBootstrapDocumentFailure.MALFORMED_DOCUMENT -> "bootstrap-document-malformed"
                SemanticRuntimeBootstrapDocumentFailure.UNSUPPORTED_SCHEMA -> "bootstrap-schema-unsupported"
            }
        is RuntimeAdmissionFailure.IntellijBootstrap -> state.failure.wireName
        RuntimeAdmissionFailure.ProcessObservationFailed -> "process-observation-failed"
        is RuntimeAdmissionFailure.WorkerControlRejected ->
            "worker-control-${failure.name.lowercase().replace('_', '-')}"
        RuntimeAdmissionFailure.EndpointUnavailable -> "endpoint-unavailable"
        RuntimeAdmissionFailure.RuntimeIdentityMismatch -> "runtime-identity-mismatch"
        RuntimeAdmissionFailure.LegacySidecarActive -> "legacy-sidecar-active"
        RuntimeAdmissionFailure.Interrupted -> "interrupted"
        is RuntimeAdmissionFailure.InstalledIdeRejected ->
            when (failure) {
                IndexSeedFailure.Ambiguity -> "idea-installation-ambiguous"
                IndexSeedFailure.MissingInstallation -> "idea-installation-missing"
                is IndexSeedFailure.Incompatibility -> "idea-installation-incompatible"
                else -> "idea-installation-rejected"
            }
        is RuntimeAdmissionFailure.SidecarCacheRejected ->
            when (failure) {
                SidecarCacheFailure.FilesystemRejected -> "sidecar-cache-rejected"
                SidecarCacheFailure.RebuildRequired -> "sidecar-cache-rebuild-required"
                is SidecarCacheFailure.ObservationRejected ->
                    "sidecar-cache-${failure.failure.name.lowercase().replace('_', '-')}"
                is SidecarCacheFailure.SeedRejected ->
                    when (failure.failure) {
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
     * Establishes that the runtime for the exact root is reachable at the requested endpoint. [RuntimeAdmissionFailure]
     * is the closed expected failure.
     */
    fun demand(
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission
}

private const val RUNTIME_PROBE_INTERVAL_MILLIS = 100L
private val RUNTIME_STARTUP_TIMEOUT: Duration = Duration.ofMillis(OperationExecutionBudget.WORKSPACE_READINESS.value)

internal sealed interface RuntimeBootstrapAttemptGeneration {
    data class Generated(val attemptId: SemanticRuntimeBootstrapAttemptId) : RuntimeBootstrapAttemptGeneration

    data object Rejected : RuntimeBootstrapAttemptGeneration
}

internal fun interface RuntimeBootstrapAttemptGenerator {
    fun generate(): RuntimeBootstrapAttemptGeneration
}

/** Exact live-process evidence for one semantic-runtime bootstrap attempt. */
internal sealed interface RuntimeBootstrapProcessObservation {
    data object Absent : RuntimeBootstrapProcessObservation

    data class Owned(
        val attemptId: SemanticRuntimeBootstrapAttemptId,
        val session: AcceptedRuntimeStartupSession,
    ) : RuntimeBootstrapProcessObservation

    /** An external service exists, but no exact child attempt can currently be proven. */
    data object Uncorrelated : RuntimeBootstrapProcessObservation

    data object Ambiguous : RuntimeBootstrapProcessObservation

    data object Interrupted : RuntimeBootstrapProcessObservation
}

/** Exact executable, endpoint, and bootstrap-state identity visible in both launcher and JVM argv. */
internal class RuntimeBootstrapProcessQuery
private constructor(
    internal val endpoint: RuntimeEndpoint,
    internal val executable: IndexerExecutable,
    internal val bootstrapState: Path,
    internal val maxHeap: io.github.amichne.kast.distribution.contract.IndexerHeapSize,
) {
    companion object {
        internal fun from(
            endpoint: RuntimeEndpoint,
            executable: IndexerExecutable,
            launchContext: SidecarLaunchContext,
        ): RuntimeBootstrapProcessQuery =
            RuntimeBootstrapProcessQuery(
                endpoint,
                executable,
                launchContext.cacheRoot.resolve(SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME),
                launchContext.maxHeap,
            )
    }
}

internal fun interface RuntimeBootstrapProcessAuthority {
    /** Correlates an exact endpoint to the live child attempt encoded in its command line. */
    fun observe(query: RuntimeBootstrapProcessQuery): RuntimeBootstrapProcessObservation
}

private object JdkRuntimeBootstrapAttemptGenerator : RuntimeBootstrapAttemptGenerator {
    override fun generate(): RuntimeBootstrapAttemptGeneration =
        try {
            when (val admitted = SemanticRuntimeBootstrapAttemptId.admit(UUID.randomUUID().toString())) {
                is Refinement.Refined -> RuntimeBootstrapAttemptGeneration.Generated(admitted.value)
                is Refinement.Rejected -> RuntimeBootstrapAttemptGeneration.Rejected
            }
        } catch (_: RuntimeException) {
            RuntimeBootstrapAttemptGeneration.Rejected
        }
}

/** Starts only the admitted indexer artifact with explicit exact-root and socket arguments. */
internal class ExactRootProcessRuntimeDemander(
    private val executable: IndexerExecutable,
    private val launchContext: SidecarLaunchContext,
    private val processStarter: RuntimeProcessStarter = JdkRuntimeProcessStarter,
    private val endpointProbe: RuntimeEndpointProbe = JdkUnixDomainEndpointProbe,
    private val attemptGenerator: RuntimeBootstrapAttemptGenerator = JdkRuntimeBootstrapAttemptGenerator,
    private val bootstrapProcessAuthority: RuntimeBootstrapProcessAuthority = JdkRuntimeBootstrapProcessAuthority,
    private val progress: RuntimeStartupProgressSink = TerminalRuntimeStartupProgress.create(),
) : RuntimeDemander {
    override fun demand(
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission {
        progress.discoveringRuntime()
        if (endpoint.root != root) {
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
        }
        val preparedEndpoint =
            when (val admission = endpoint.prepareTransport()) {
                is RuntimeEndpointResolution.Resolved -> admission.endpoint
                is RuntimeEndpointResolution.Rejected ->
                    return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
            }
        return when (
            val execution =
                SidecarBootstrapAttemptLock.withAcquired(
                    launchContext.cacheRoot,
                    RUNTIME_STARTUP_TIMEOUT,
                ) {
                    demandExclusively(root, preparedEndpoint)
                }
        ) {
            is SidecarBootstrapAttemptLockExecution.Executed -> execution.value
            SidecarBootstrapAttemptLockExecution.Interrupted ->
                RuntimeAdmission.Rejected(RuntimeAdmissionFailure.Interrupted)
            SidecarBootstrapAttemptLockExecution.Rejected,
            SidecarBootstrapAttemptLockExecution.TimedOut ->
                RuntimeAdmission.Rejected(RuntimeAdmissionFailure.BootstrapAttemptLockUnavailable)
        }
    }

    private fun demandExclusively(
        root: CanonicalRoot,
        endpoint: RuntimeEndpoint,
    ): RuntimeAdmission {
        val processQuery =
            RuntimeBootstrapProcessQuery.from(
                endpoint,
                executable,
                launchContext,
            )
        val bootstrapState = launchContext.cacheRoot.resolve(SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME)
        if (endpointProbe.probe(endpoint) is RuntimeEndpointReachability.Reachable) {
            when (val admission = endpoint.reachableBootstrapAdmission(bootstrapState)) {
                is ReachableBootstrapAdmission.Ready -> return RuntimeAdmission.Ready(endpoint)
                is ReachableBootstrapAdmission.Rejected -> return RuntimeAdmission.Rejected(admission.failure)
                is ReachableBootstrapAdmission.Starting ->
                    return when (val process = bootstrapProcessAuthority.observe(processQuery)) {
                        is RuntimeBootstrapProcessObservation.Owned ->
                            if (process.attemptId == admission.attemptId) {
                                awaitBootstrapAttempt(
                                    endpoint,
                                    bootstrapState,
                                    process.attemptId,
                                    process.session,
                                )
                            } else {
                                RuntimeAdmission.Rejected(RuntimeAdmissionFailure.BootstrapAttemptMismatch)
                            }
                        RuntimeBootstrapProcessObservation.Absent ->
                            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.SessionEndedBeforeReady)
                        RuntimeBootstrapProcessObservation.Uncorrelated ->
                            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.BootstrapAttemptUnavailable)
                        RuntimeBootstrapProcessObservation.Ambiguous ->
                            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ProcessObservationFailed)
                        RuntimeBootstrapProcessObservation.Interrupted ->
                            RuntimeAdmission.Rejected(RuntimeAdmissionFailure.Interrupted)
                    }
            }
        }
        when (val process = bootstrapProcessAuthority.observe(processQuery)) {
            is RuntimeBootstrapProcessObservation.Owned ->
                return awaitBootstrapAttempt(
                    endpoint,
                    bootstrapState,
                    process.attemptId,
                    process.session,
                )
            RuntimeBootstrapProcessObservation.Absent -> Unit
            RuntimeBootstrapProcessObservation.Uncorrelated ->
                return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.BootstrapAttemptUnavailable)
            RuntimeBootstrapProcessObservation.Ambiguous ->
                return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ProcessObservationFailed)
            RuntimeBootstrapProcessObservation.Interrupted ->
                return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.Interrupted)
        }
        if (
            SidecarCacheStateFile.record(
                launchContext.cacheRoot,
                KastCacheState.REFRESHING,
            ) != CacheStateTransition.Recorded
        ) {
            return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.SidecarCacheRejected(SidecarCacheFailure.FilesystemRejected)
            )
        }
        val attemptId =
            when (val generation = attemptGenerator.generate()) {
                is RuntimeBootstrapAttemptGeneration.Generated -> generation.attemptId
                RuntimeBootstrapAttemptGeneration.Rejected ->
                    return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.BootstrapAttemptUnavailable)
            }
        val command =
            when (
                val construction =
                    IndexerLaunchCommand.create(
                        executable,
                        root,
                        endpoint,
                        launchContext,
                        attemptId,
                    )
            ) {
                is IndexerLaunchCommandConstruction.Created -> construction.command
                is IndexerLaunchCommandConstruction.Rejected ->
                    return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
            }
        val started =
            when (val start = processStarter.start(command)) {
                is RuntimeProcessStart.Started -> start
                is RuntimeProcessStart.ExistingSession ->
                    return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.BootstrapAttemptUnavailable)
                RuntimeProcessStart.Interrupted -> return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.Interrupted)
                is RuntimeProcessStart.Rejected ->
                    return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ProcessStartFailed(start.failure))
            }
        if (started.attemptId != command.bootstrapAttemptId) {
            started.session.terminate()
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.BootstrapAttemptMismatch)
        }
        return awaitOwnedBootstrapAttempt(
            endpoint,
            command.bootstrapState,
            command.bootstrapAttemptId,
            started.session,
        )
    }

    /** A newly started cold bootstrap is owned only until readiness is proven. */
    private fun awaitOwnedBootstrapAttempt(
        endpoint: RuntimeEndpoint,
        bootstrapState: Path,
        attemptId: SemanticRuntimeBootstrapAttemptId,
        session: AcceptedRuntimeStartupSession,
    ): RuntimeAdmission {
        val lifetime = ColdBootstrapLifetime(session)
        if (!lifetime.arm()) {
            lifetime.close()
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.Interrupted)
        }
        var ready = false
        return try {
            val admission = awaitBootstrapAttempt(endpoint, bootstrapState, attemptId, session)
            ready = admission is RuntimeAdmission.Ready
            admission
        } finally {
            if (!ready) lifetime.close()
            lifetime.disarm()
        }
    }

    private fun awaitBootstrapAttempt(
        endpoint: RuntimeEndpoint,
        bootstrapState: Path,
        attemptId: SemanticRuntimeBootstrapAttemptId,
        session: AcceptedRuntimeStartupSession,
    ): RuntimeAdmission {
        val deadline = System.nanoTime() + RUNTIME_STARTUP_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            val reachability = endpointProbe.probe(endpoint)
            val bootstrap = SidecarBootstrapStateFile.observe(bootstrapState)
            when (bootstrap) {
                is SidecarBootstrapStateObservation.Observed -> {
                    val state = bootstrap.state
                    val owned = state.attemptId == attemptId
                    if (owned) {
                        when (state) {
                            is SemanticRuntimeBootstrapState.Ready ->
                                if (reachability is RuntimeEndpointReachability.Reachable) {
                                    progress.publish(state)
                                    return RuntimeAdmission.Ready(endpoint)
                                }
                            is SemanticRuntimeBootstrapState.Rejected -> {
                                progress.publish(state)
                                return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.IntellijBootstrap(state))
                            }
                            is SemanticRuntimeBootstrapState.Starting -> progress.publish(state)
                        }
                    }
                }
                is SidecarBootstrapStateObservation.Rejected -> Unit
            }
            when (session.observe()) {
                RuntimeSessionObservation.Present -> Unit
                RuntimeSessionObservation.Absent ->
                    return RuntimeAdmission.Rejected(bootstrap.sessionEndedBeforeReadyFailure(attemptId))
                RuntimeSessionObservation.Rejected ->
                    return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ProcessObservationFailed)
                RuntimeSessionObservation.Interrupted ->
                    return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.Interrupted)
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

/** Process-shutdown guard for one exact newly started sidecar bootstrap. */
private class ColdBootstrapLifetime(private val session: AcceptedRuntimeStartupSession) {
    private val closed = AtomicBoolean(false)
    private val hook = Thread(::close, "kast-cold-bootstrap-shutdown")
    private var armed = false

    fun arm(): Boolean =
        try {
            Runtime.getRuntime().addShutdownHook(hook)
            armed = true
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }

    fun disarm() {
        if (!armed) return
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // JVM shutdown owns the registered hook.
        } catch (_: SecurityException) {
            // The registered hook still owns closure if removal is denied.
        } finally {
            armed = false
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val interrupted = Thread.interrupted()
        try {
            session.terminate()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

private sealed interface ReachableBootstrapAdmission {
    data object Ready : ReachableBootstrapAdmission

    data class Starting(val attemptId: SemanticRuntimeBootstrapAttemptId) : ReachableBootstrapAdmission

    data class Rejected(val failure: RuntimeAdmissionFailure) : ReachableBootstrapAdmission
}

private fun RuntimeEndpoint.reachableBootstrapAdmission(bootstrapState: Path): ReachableBootstrapAdmission =
    when (val observation = SidecarBootstrapStateFile.observe(bootstrapState)) {
        is SidecarBootstrapStateObservation.Observed ->
            when (val state = observation.state) {
                is SemanticRuntimeBootstrapState.Ready -> ReachableBootstrapAdmission.Ready
                is SemanticRuntimeBootstrapState.Rejected ->
                    ReachableBootstrapAdmission.Rejected(RuntimeAdmissionFailure.BootstrapAttemptMismatch)
                is SemanticRuntimeBootstrapState.Starting -> ReachableBootstrapAdmission.Starting(state.attemptId)
            }
        is SidecarBootstrapStateObservation.Rejected ->
            when (val failure = observation.failure) {
                is SidecarBootstrapStateFileFailure.DocumentRejected ->
                    ReachableBootstrapAdmission.Rejected(
                        RuntimeAdmissionFailure.BootstrapProtocolRejected(failure.failure)
                    )
                SidecarBootstrapStateFileFailure.FilesystemRejected,
                SidecarBootstrapStateFileFailure.PathRejected ->
                    ReachableBootstrapAdmission.Rejected(RuntimeAdmissionFailure.BootstrapStateUnavailable)
            }
    }

/** Preserves only a terminal state proven to belong to the accepted startup session. */
private fun SidecarBootstrapStateObservation.sessionEndedBeforeReadyFailure(
    startedAttempt: SemanticRuntimeBootstrapAttemptId
): RuntimeAdmissionFailure =
    when (this) {
        is SidecarBootstrapStateObservation.Observed -> {
            when (val state = state) {
                is SemanticRuntimeBootstrapState.Rejected ->
                    if (state.attemptId == startedAttempt) {
                        RuntimeAdmissionFailure.IntellijBootstrap(state)
                    } else {
                        RuntimeAdmissionFailure.SessionEndedBeforeReady
                    }
                is SemanticRuntimeBootstrapState.Ready,
                is SemanticRuntimeBootstrapState.Starting -> RuntimeAdmissionFailure.SessionEndedBeforeReady
            }
        }
        is SidecarBootstrapStateObservation.Rejected ->
            when (val failure = failure) {
                is SidecarBootstrapStateFileFailure.DocumentRejected -> RuntimeAdmissionFailure.SessionEndedBeforeReady
                SidecarBootstrapStateFileFailure.FilesystemRejected,
                SidecarBootstrapStateFileFailure.PathRejected -> RuntimeAdmissionFailure.SessionEndedBeforeReady
            }
    }

/** Realizes the exact manifest runtime before delegating exact-root process admission. */
fun interface InstalledSemanticRuntimeResolver {
    /**
     * Proof transition: `SemanticRuntimeManifest -> SemanticRuntimeResolution`.
     *
     * Establishes one verified installed runtime carrying the manifest identity, or returns the closed
     * [RuntimeStoreFailure] represented by [SemanticRuntimeResolution.Rejected]. Raw paths may leave the installed
     * capability only at process launch.
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
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.RuntimeIdentityMismatch)
        }
        val installed =
            when (val resolution = resolver.resolve(manifest)) {
                is SemanticRuntimeResolution.Installed -> resolution.runtime
                is SemanticRuntimeResolution.Rejected ->
                    return RuntimeAdmission.Rejected(resolution.failure.toAdmissionFailure())
            }
        val executable =
            when (val admitted = IndexerExecutable.admit(installed.executable)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.LayoutInvalid)
            }
        val context = launchContext ?: return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.LayoutInvalid)
        return ExactRootProcessRuntimeDemander(executable, context).demand(root, endpoint)
    }
}

private fun RuntimeStoreFailure.toAdmissionFailure(): RuntimeAdmissionFailure =
    when (this) {
        RuntimeStoreFailure.STORE_INVALID -> RuntimeAdmissionFailure.SourceInvalid
        RuntimeStoreFailure.ARTIFACT_UNAVAILABLE -> RuntimeAdmissionFailure.ArtifactUnavailable
        RuntimeStoreFailure.DIGEST_MISMATCH -> RuntimeAdmissionFailure.DigestMismatch
        RuntimeStoreFailure.ARCHIVE_REJECTED -> RuntimeAdmissionFailure.ArchiveRejected
        RuntimeStoreFailure.LAYOUT_INVALID -> RuntimeAdmissionFailure.LayoutInvalid
        RuntimeStoreFailure.RUNTIME_INCOMPATIBLE -> RuntimeAdmissionFailure.RuntimeIncompatible
        RuntimeStoreFailure.INTERRUPTED -> RuntimeAdmissionFailure.Interrupted
    }
