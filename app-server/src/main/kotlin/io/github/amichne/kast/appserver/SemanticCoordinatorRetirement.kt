package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.BrokerControlRoute
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.connectCodexUnixWebSocket
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class SemanticCoordinatorRetirement {
    ABSENT,
    EXITED,
    UNFENCED,
    OWNERSHIP_UNPROVEN,
    SHUTDOWN_UNOBSERVED,
    EXIT_UNOBSERVED,
}

/** The caller owns the installation transition. This holds the exact peer through the retirement exchange. */
suspend fun retireFencedSemanticCoordinator(installation: Path, home: Path): SemanticCoordinatorRetirement {
    if (InstallationLifecycleFence.observe(installation) != InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS)
        return SemanticCoordinatorRetirement.UNFENCED
    val readiness = InstalledSemanticCoordinator.readinessPath(installation, home)
    val executable = installation.resolve(MANAGEMENT_EXECUTABLE)
    return when (val state = SemanticPublicationState.inspectOwned(installation, readiness, executable)) {
        SemanticPublicationState.Missing -> SemanticCoordinatorRetirement.ABSENT
        is SemanticPublicationState.Stale -> SemanticCoordinatorRetirement.EXITED
        is SemanticPublicationState.Published -> retirePublished(installation, state.publication)
        is SemanticPublicationState.Starting,
        is SemanticPublicationState.Rejected -> SemanticCoordinatorRetirement.OWNERSHIP_UNPROVEN
    }
}

private suspend fun retirePublished(
    installation: Path,
    publication: SemanticPublication,
): SemanticCoordinatorRetirement {
    if (!publication.current()) return SemanticCoordinatorRetirement.OWNERSHIP_UNPROVEN
    val acknowledged =
        withTimeoutOrNull(BrokerOperationalLimits.managementExchange.value) {
            val connection =
                when (
                    val selected =
                        connectCodexUnixWebSocket(
                            publication.socket.path,
                            DaemonManagementProtocol.maximumBytes,
                            BrokerOperationalLimits.managementExchange.value,
                            BrokerControlRoute.MANAGEMENT,
                        )
                ) {
                    is BrokerUpstreamConnectionAdmission.Connected -> selected.connection
                    BrokerUpstreamConnectionAdmission.Rejected -> return@withTimeoutOrNull false
                }
            try {
                if (
                    InstallationLifecycleFence.observe(installation) !=
                        InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS || !publication.current()
                )
                    return@withTimeoutOrNull false
                val reply = exchangeDaemonManagement(connection, DaemonManagementRequest.Retire(publication.generation))
                val accepted = (reply as? Refinement.Refined)?.value as? DaemonManagementResponse.Retiring
                accepted?.target?.serviceGeneration == publication.generation
            } finally {
                withContext(NonCancellable) { connection.close() }
            }
        }
    if (acknowledged != true) return SemanticCoordinatorRetirement.SHUTDOWN_UNOBSERVED
    return awaitExit(publication.peer)
}

private suspend fun awaitExit(peer: SemanticCoordinatorPeer): SemanticCoordinatorRetirement =
    withTimeoutOrNull(BrokerOperationalLimits.serviceRetirement.value) {
        while (peer.incarnation() == SemanticPeerIncarnation.LIVE) delay(BrokerOperationalLimits.servicePoll.value)
        if (peer.incarnation() == SemanticPeerIncarnation.EXITED) SemanticCoordinatorRetirement.EXITED
        else SemanticCoordinatorRetirement.OWNERSHIP_UNPROVEN
    } ?: SemanticCoordinatorRetirement.EXIT_UNOBSERVED
