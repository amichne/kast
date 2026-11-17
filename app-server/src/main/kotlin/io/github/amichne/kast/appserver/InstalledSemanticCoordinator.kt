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
import kotlinx.serialization.SerializationException

/** A live service generation correlated with the private readiness record and its socket. */
class InstalledSemanticCoordinator
private constructor(
    private val client: InstalledDaemonOperationClient,
    val generation: String,
    val processId: Long,
) {
    suspend fun execute(root: CanonicalRoot, call: DaemonOperationCall): DaemonOperationResult =
        client.exchange(root, call)

    companion object {
        fun readinessPath(installation: Path, home: Path): Path =
            BrokerInstallationLayout.from(installation.resolve("bin/kast"), home.resolve(".codex"))
                .broker
                .resolve("service-readiness.json")

        suspend fun observe(
            installation: Path,
            readiness: Path,
        ): Refinement<InstalledSemanticCoordinator, WorkerControlFailure> =
            observe(installation, readiness, installation.resolve("share/kast/libexec/kast-management"))

        internal suspend fun observe(
            installation: Path,
            readiness: Path,
            executable: Path,
        ): Refinement<InstalledSemanticCoordinator, WorkerControlFailure> {
            val publication =
                SemanticPublication.read(installation, readiness, executable)
                    ?: return Refinement.Rejected(missingPublication(installation, readiness))
            val snapshot =
                when (val status = readCoordinatorStatus(publication.socket.path)) {
                    is Refinement.Refined -> status.value
                    is Refinement.Rejected -> return Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
                }
            if (!publication.admits(snapshot)) return Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
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

private fun missingPublication(installation: Path, readiness: Path): WorkerControlFailure =
    if (
        Files.notExists(readiness, NOFOLLOW_LINKS) &&
            Files.notExists(installation.resolve("state/run/c.sock"), NOFOLLOW_LINKS)
    )
        WorkerControlFailure.UNAVAILABLE
    else WorkerControlFailure.IDENTITY_REJECTED

private class SemanticPublication
private constructor(
    private val installation: Path,
    private val path: Path,
    private val service: BrokerServiceStateDocument.Ready,
    private val executable: Path,
    val socket: BrokerSocketPath,
) {
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
            privateReadiness(installation, path) == service &&
            privateSocket(installation, socket.physicalPath) &&
            service.semanticPeer?.owns(socket, executable) == true

    companion object {
        fun read(installation: Path, path: Path, executable: Path): SemanticPublication? {
            val service = privateReadiness(installation, path) ?: return null
            val physical = installation.resolve("state/run/c.sock")
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

private fun privateReadiness(installation: Path, path: Path): BrokerServiceStateDocument.Ready? {
    if (
        path.parent?.parent != installation.resolve("state/broker") ||
            path.fileName.toString() != "service-readiness.json"
    )
        return null
    return try {
        val owner = Files.getOwner(installation, NOFOLLOW_LINKS)
        if (
            !ownedDirectory(path.parent, owner) ||
                !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                !ownedMode(path, owner, privateFile)
        )
            return null
        val bytes =
            Files.newInputStream(path, NOFOLLOW_LINKS).use {
                it.readNBytes(BrokerOperationalLimits.maximumControlStateBytes + 1)
            }
        if (bytes.size > BrokerOperationalLimits.maximumControlStateBytes) return null
        val document =
            BROKER_SERVICE_STATE_JSON.decodeFromString<BrokerServiceStateDocument>(bytes.toString(Charsets.UTF_8))
        (document as? BrokerServiceStateDocument.Ready)?.takeIf {
            it.schemaVersion == BROKER_SERVICE_STATE_SCHEMA_VERSION && it.semanticPeer != null
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: SerializationException) {
        null
    }
}

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
