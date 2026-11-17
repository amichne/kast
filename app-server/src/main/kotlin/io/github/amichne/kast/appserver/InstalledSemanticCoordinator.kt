package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.BrokerSocketPath
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** A live service generation correlated with the private readiness record and its socket. */
class InstalledSemanticCoordinator
private constructor(
    private val client: InstalledDaemonOperationClient,
    val generation: String,
    val processId: Long,
) {
    /** States this process's environment-tier settings. Persistent adapters pass their admitted environment. */
    suspend fun execute(root: CanonicalRoot, call: DaemonOperationCall): DaemonOperationResult =
        execute(root, call, RequestedRuntimeSettings.fromEnvironment(System.getenv()))

    suspend fun execute(
        root: CanonicalRoot,
        call: DaemonOperationCall,
        settings: RequestedRuntimeSettings = RequestedRuntimeSettings.Unstated,
    ): DaemonOperationResult = client.exchange(root, call, settings)

    companion object {
        fun readinessPath(installation: Path, home: Path): Path =
            BrokerInstallationLayout.from(installation.resolve("bin/kast"), home.resolve(".codex"))
                .broker
                .resolve("service-readiness.json")

        suspend fun observe(
            installation: Path,
            readiness: Path,
        ): Refinement<InstalledSemanticCoordinator, WorkerControlFailure> =
            observe(installation, readiness, installation.resolve(MANAGEMENT_EXECUTABLE))

        /** Passive observation: an absent publication is [WorkerControlFailure.UNAVAILABLE]; nothing starts. */
        internal suspend fun observe(
            installation: Path,
            readiness: Path,
            executable: Path,
        ): Refinement<InstalledSemanticCoordinator, WorkerControlFailure> =
            when (val state = SemanticPublicationState.inspect(installation, readiness, executable)) {
                is SemanticPublicationState.Published ->
                    when (val live = live(state.publication)) {
                        is Refinement.Refined -> live
                        is Refinement.Rejected -> Refinement.Rejected(live.failure.workerFailure())
                    }
                SemanticPublicationState.Missing -> Refinement.Rejected(WorkerControlFailure.UNAVAILABLE)
                is SemanticPublicationState.Starting -> Refinement.Rejected(WorkerControlFailure.LIFECYCLE_TRANSITION)
                is SemanticPublicationState.Stale -> Refinement.Rejected(WorkerControlFailure.RECOVERY_REQUIRED)
                is SemanticPublicationState.Rejected -> Refinement.Rejected(state.rejection.workerFailure())
            }

        /**
         * Starts or joins this installation's semantic coordinator. [submit] runs only when no owned generation is live
         * or starting; the supervisor repeats that decision under the installation start lock.
         */
        suspend fun connect(
            installation: Path,
            home: Path,
            submit: suspend () -> Refinement<Unit, PersistentBrokerServiceFailure>,
        ): Refinement<InstalledSemanticCoordinator, SemanticCoordinatorStartupFailure> =
            connect(
                installation,
                readinessPath(installation, home),
                installation.resolve(MANAGEMENT_EXECUTABLE),
                submit,
                BrokerOperationalLimits.serviceStartup.value,
            ) {
                delay(READINESS_POLL_MILLIS)
            }

        internal suspend fun connect(
            installation: Path,
            readiness: Path,
            executable: Path,
            submit: suspend () -> Refinement<Unit, PersistentBrokerServiceFailure>,
            budgetMillis: Long,
            pause: suspend () -> Unit,
        ): Refinement<InstalledSemanticCoordinator, SemanticCoordinatorStartupFailure> =
            withTimeoutOrNull(budgetMillis) { startup(installation, readiness, executable, submit, pause) }
                ?: Refinement.Rejected(SemanticCoordinatorStartupFailure.StartupTimedOut)

        private suspend fun startup(
            installation: Path,
            readiness: Path,
            executable: Path,
            submit: suspend () -> Refinement<Unit, PersistentBrokerServiceFailure>,
            pause: suspend () -> Unit,
        ): Refinement<InstalledSemanticCoordinator, SemanticCoordinatorStartupFailure> {
            var progress: SemanticStartupProgress = SemanticStartupProgress.BeforeSubmission
            while (true) {
                when (val next = progress.next(SemanticPublicationState.inspect(installation, readiness, executable))) {
                    is SemanticStartupStep.Connect -> return connectPublished(next.publication)
                    is SemanticStartupStep.Rejected -> return Refinement.Rejected(next.failure)
                    is SemanticStartupStep.Await -> {
                        progress = next.progress
                        pause()
                    }
                    SemanticStartupStep.Submit ->
                        when (val admitted = submit()) {
                            is Refinement.Refined -> progress = SemanticStartupProgress.AwaitingPublication
                            is Refinement.Rejected ->
                                return Refinement.Rejected(
                                    SemanticCoordinatorStartupFailure.SubmissionRejected(admitted.failure)
                                )
                        }
                }
            }
        }

        private suspend fun connectPublished(
            publication: SemanticPublication
        ): Refinement<InstalledSemanticCoordinator, SemanticCoordinatorStartupFailure> =
            when (val connected = live(publication)) {
                is Refinement.Refined -> connected
                is Refinement.Rejected -> Refinement.Rejected(connected.failure.startupFailure())
            }

        private suspend fun live(
            publication: SemanticPublication
        ): Refinement<InstalledSemanticCoordinator, SemanticPublicationRejection> {
            val snapshot =
                when (val status = readCoordinatorStatus(publication.socket.path)) {
                    is Refinement.Refined -> status.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
                }
            if (!publication.admits(snapshot))
                return Refinement.Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
            val target = DaemonManagementTarget.from(snapshot)
            return Refinement.Refined(
                InstalledSemanticCoordinator(
                    InstalledDaemonOperationClient(publication.socket.path, target) { publication.admits(snapshot) },
                    target.serviceGeneration,
                    publication.processId,
                )
            )
        }
    }
}

private sealed interface SemanticStartupStep {
    data object Submit : SemanticStartupStep

    data class Await(val progress: SemanticStartupProgress) : SemanticStartupStep

    data class Connect(val publication: SemanticPublication) : SemanticStartupStep

    data class Rejected(val failure: SemanticCoordinatorStartupFailure) : SemanticStartupStep
}

private sealed interface SemanticStartupProgress {
    data object BeforeSubmission : SemanticStartupProgress

    data object AwaitingPublication : SemanticStartupProgress

    data class AwaitingGeneration(val generation: String) : SemanticStartupProgress

    fun next(state: SemanticPublicationState): SemanticStartupStep =
        when (state) {
            is SemanticPublicationState.Published ->
                if (allows(state.publication.generation)) SemanticStartupStep.Connect(state.publication) else replaced()
            is SemanticPublicationState.Starting ->
                if (allows(state.generation)) SemanticStartupStep.Await(AwaitingGeneration(state.generation))
                else replaced()
            SemanticPublicationState.Missing ->
                when (this) {
                    BeforeSubmission -> SemanticStartupStep.Submit
                    AwaitingPublication -> SemanticStartupStep.Await(this)
                    is AwaitingGeneration -> replaced()
                }
            is SemanticPublicationState.Stale ->
                if (this == BeforeSubmission) SemanticStartupStep.Submit else replaced()
            is SemanticPublicationState.Rejected -> SemanticStartupStep.Rejected(state.rejection.startupFailure())
        }

    private fun allows(generation: String): Boolean = this !is AwaitingGeneration || this.generation == generation

    private fun replaced() = SemanticStartupStep.Rejected(SemanticCoordinatorStartupFailure.GenerationReplaced)
}

/** Closed outcomes for a client that starts or joins the installed semantic coordinator. */
sealed interface SemanticCoordinatorStartupFailure {
    /** An installation lifecycle transition, such as replacement, fences service admission. */
    data object InstallationFenced : SemanticCoordinatorStartupFailure

    /** The publication, socket, or recorded process does not prove this installation's ownership. */
    data object OwnershipRejected : SemanticCoordinatorStartupFailure

    /** The owned startup of the observed generation published a rejection. */
    data object StartupRejected : SemanticCoordinatorStartupFailure

    /** The awaited generation was replaced or retired before it became ready. */
    data object GenerationReplaced : SemanticCoordinatorStartupFailure

    /** The explicit startup budget expired before the awaited generation became ready. */
    data object StartupTimedOut : SemanticCoordinatorStartupFailure

    /** The supervisor submission or its admission rejected with this exact cause. */
    data class SubmissionRejected(val failure: PersistentBrokerServiceFailure) : SemanticCoordinatorStartupFailure
}

internal enum class SemanticPublicationRejection {
    INSTALLATION_FENCED,
    OWNERSHIP_REJECTED,
    STARTUP_REJECTED;

    fun workerFailure(): WorkerControlFailure =
        when (this) {
            INSTALLATION_FENCED -> WorkerControlFailure.LIFECYCLE_TRANSITION
            OWNERSHIP_REJECTED -> WorkerControlFailure.IDENTITY_REJECTED
            STARTUP_REJECTED -> WorkerControlFailure.STARTUP_REJECTED
        }

    fun startupFailure(): SemanticCoordinatorStartupFailure =
        when (this) {
            INSTALLATION_FENCED -> SemanticCoordinatorStartupFailure.InstallationFenced
            OWNERSHIP_REJECTED -> SemanticCoordinatorStartupFailure.OwnershipRejected
            STARTUP_REJECTED -> SemanticCoordinatorStartupFailure.StartupRejected
        }

    fun serviceFailure(): PersistentBrokerServiceFailure =
        when (this) {
            INSTALLATION_FENCED -> PersistentBrokerServiceFailure.DISABLED
            OWNERSHIP_REJECTED,
            STARTUP_REJECTED -> PersistentBrokerServiceFailure.READINESS_REJECTED
        }
}

/** File-level publication facts for one installation; a status exchange follows only for [Published]. */
internal sealed interface SemanticPublicationState {
    /** Neither a readiness document nor a socket exists; a submission may start the first generation. */
    data object Missing : SemanticPublicationState

    /** This installation's service is publishing [generation]; clients wait for it instead of submitting. */
    data class Starting(val generation: String) : SemanticPublicationState

    class Published internal constructor(val publication: SemanticPublication) : SemanticPublicationState

    /** This installation's ready publication whose recorded process incarnation provably exited. */
    class Stale
    internal constructor(
        val installation: Path,
        val path: Path,
        val document: BrokerServiceStateDocument.Ready,
        val peer: SemanticCoordinatorPeer,
    ) : SemanticPublicationState

    data class Rejected(val rejection: SemanticPublicationRejection) : SemanticPublicationState

    companion object {
        fun inspect(installation: Path, path: Path, executable: Path): SemanticPublicationState {
            if (InstallationLifecycleFence.observe(installation) != InstallationLifecycleStartAdmission.AVAILABLE)
                return Rejected(SemanticPublicationRejection.INSTALLATION_FENCED)
            val document =
                when (val read = privateDocument(installation, path)) {
                    PrivateServiceDocument.Absent ->
                        return if (Files.notExists(installation.resolve(SEMANTIC_SOCKET), NOFOLLOW_LINKS)) Missing
                        else Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
                    PrivateServiceDocument.Unadmitted ->
                        return Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
                    is PrivateServiceDocument.Admitted -> read.document
                }
            if (
                document.schemaVersion != BROKER_SERVICE_STATE_SCHEMA_VERSION ||
                    document.serviceIdentity != semanticServiceIdentity(installation)
            )
                return Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
            return when (document) {
                is BrokerServiceStateDocument.Starting -> Starting(document.serviceInstanceId)
                is BrokerServiceStateDocument.Rejected -> Rejected(SemanticPublicationRejection.STARTUP_REJECTED)
                is BrokerServiceStateDocument.Ready -> {
                    val peer = document.semanticPeer ?: return Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
                    when (peer.incarnation()) {
                        SemanticPeerIncarnation.EXITED -> Stale(installation, path, document, peer)
                        SemanticPeerIncarnation.UNPROVEN -> Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
                        SemanticPeerIncarnation.LIVE ->
                            SemanticPublication.read(installation, path, document, executable)?.let(::Published)
                                ?: Rejected(SemanticPublicationRejection.OWNERSHIP_REJECTED)
                    }
                }
            }
        }
    }
}

internal class SemanticPublication
private constructor(
    private val installation: Path,
    private val path: Path,
    private val service: BrokerServiceStateDocument.Ready,
    private val executable: Path,
    val socket: BrokerSocketPath,
) {
    val generation: String
        get() = service.serviceInstanceId

    val processId: Long
        get() = checkNotNull(service.semanticPeer).pid

    fun admits(snapshot: CoordinatorStatusSnapshot): Boolean {
        val owner =
            when (
                val admitted =
                    BrokerInstallationState.observeCopiedEpoch(
                        installation.resolve("state"),
                        snapshot.observed.installationId,
                    )
            ) {
                is Refinement.Rejected -> return false
                is Refinement.Refined -> admitted.value
            }
        return snapshot.belongsTo(owner, service) && current()
    }

    private fun current(): Boolean =
        InstallationLifecycleFence.observe(installation) == InstallationLifecycleStartAdmission.AVAILABLE &&
            (privateDocument(installation, path) as? PrivateServiceDocument.Admitted)?.document == service &&
            privateSocket(installation, socket.physicalPath) &&
            service.semanticPeer?.owns(socket, executable) == true

    companion object {
        fun read(
            installation: Path,
            path: Path,
            service: BrokerServiceStateDocument.Ready,
            executable: Path,
        ): SemanticPublication? {
            val physical = installation.resolve(SEMANTIC_SOCKET)
            val endpoint = BrokerEndpointAliases.transportPath(physical)
            val socket =
                when (val admitted = BrokerSocketPath.observe(endpoint)) {
                    is Validation.Rejected -> return null
                    is Validation.Validated -> admitted.value
                }
            if (socket.physicalPath != physical) return null
            val publication = SemanticPublication(installation, path, service, executable, socket)
            return publication.takeIf { it.current() }
        }
    }
}

private sealed interface PrivateServiceDocument {
    data object Absent : PrivateServiceDocument

    data object Unadmitted : PrivateServiceDocument

    data class Admitted(val document: BrokerServiceStateDocument) : PrivateServiceDocument
}

private fun privateDocument(installation: Path, path: Path): PrivateServiceDocument {
    if (
        path.parent?.parent != installation.resolve("state/broker") ||
            path.fileName.toString() != "service-readiness.json"
    )
        return PrivateServiceDocument.Unadmitted
    return try {
        if (Files.notExists(path, NOFOLLOW_LINKS)) return PrivateServiceDocument.Absent
        val owner = Files.getOwner(installation, NOFOLLOW_LINKS)
        if (!ownedReadinessFile(path, owner)) return PrivateServiceDocument.Unadmitted
        val bytes =
            Files.newInputStream(path, NOFOLLOW_LINKS).use {
                it.readNBytes(BrokerOperationalLimits.maximumControlStateBytes + 1)
            }
        if (bytes.size > BrokerOperationalLimits.maximumControlStateBytes) return PrivateServiceDocument.Unadmitted
        PrivateServiceDocument.Admitted(
            BROKER_SERVICE_STATE_JSON.decodeFromString<BrokerServiceStateDocument>(bytes.toString(Charsets.UTF_8))
        )
    } catch (_: IOException) {
        PrivateServiceDocument.Unadmitted
    } catch (_: SecurityException) {
        PrivateServiceDocument.Unadmitted
    } catch (_: SerializationException) {
        PrivateServiceDocument.Unadmitted
    } catch (_: IllegalArgumentException) {
        PrivateServiceDocument.Unadmitted
    }
}

private fun ownedReadinessFile(path: Path, owner: UserPrincipal): Boolean =
    ownedDirectory(path.parent, owner) &&
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
        ownedMode(path, owner, privateFile)

private fun privateSocket(installation: Path, path: Path): Boolean =
    try {
        val owner = Files.getOwner(installation, NOFOLLOW_LINKS)
        ownedDirectory(path.parent, owner) && ownedMode(path, owner, privateFile)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun ownedDirectory(path: Path, owner: UserPrincipal): Boolean =
    Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path && ownedMode(path, owner, privateDirectory)

private fun ownedMode(path: Path, owner: UserPrincipal, mode: Set<PosixFilePermission>): Boolean =
    Files.getOwner(path, NOFOLLOW_LINKS) == owner && Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == mode

private val privateDirectory = PosixFilePermissions.fromString("rwx------")
private val privateFile = PosixFilePermissions.fromString("rw-------")

private const val MANAGEMENT_EXECUTABLE = "share/kast/libexec/kast-management"
private const val SEMANTIC_SOCKET = "state/run/c.sock"
private const val READINESS_POLL_MILLIS = 25L
