package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.BrokerSocketPath
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlinx.serialization.SerializationException

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
            return inspectOwned(installation, path, executable)
        }

        fun inspectOwned(installation: Path, path: Path, executable: Path): SemanticPublicationState {
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

    val peer: SemanticCoordinatorPeer
        get() = checkNotNull(service.semanticPeer)

    val processId: Long
        get() = peer.pid

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
        return snapshot.belongsTo(owner, service) &&
            InstallationLifecycleFence.observe(installation) == InstallationLifecycleStartAdmission.AVAILABLE &&
            current()
    }

    fun current(): Boolean =
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

internal const val MANAGEMENT_EXECUTABLE = "share/kast/libexec/kast-management"
private const val SEMANTIC_SOCKET = "state/run/c.sock"
