package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import java.nio.file.Path
import io.github.amichne.kast.appserver.runtime.WorkerReservationPhase
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class AppliedConfigurationScope { INSTALLATION, WORKSPACE }
@Serializable enum class AppliedConfigurationUnavailable { INSTALLATION_UNSELECTED, COORDINATOR_UNAVAILABLE, OWNER_REJECTED, WORKER_UNOBSERVED, CONFIGURATION_UNOBSERVED }

@Serializable enum class AppliedWorkerPhase { RESERVED, STARTING, READY, QUARANTINED_STARTUP, QUARANTINED_RUNTIME }
@Serializable enum class AppliedHeapObservation { UNOBSERVED }
@Serializable sealed interface ConfigurationAcknowledgementEvidence {
    @Serializable @SerialName("coordinator") data object Coordinator : ConfigurationAcknowledgementEvidence
    @Serializable @SerialName("worker-admission") data class Worker(
        val phase: AppliedWorkerPhase, val requestedHeapMiB: Int, val heapObservation: AppliedHeapObservation = AppliedHeapObservation.UNOBSERVED,
    ) : ConfigurationAcknowledgementEvidence
}

@Serializable sealed interface AppliedConfigurationInspection {
    @Serializable @SerialName("unobserved") data class Unobserved(val reason: AppliedConfigurationUnavailable) : AppliedConfigurationInspection
    @Serializable @SerialName("acknowledged") data class Acknowledged(
        val scope: AppliedConfigurationScope, val configurationIdentity: String, val serviceGeneration: String,
        val evidence: ConfigurationAcknowledgementEvidence,
    ) : AppliedConfigurationInspection
    @Serializable @SerialName("pending") data class Pending(
        val scope: AppliedConfigurationScope, val desiredIdentity: String, val acknowledgedIdentity: String,
        val serviceGeneration: String, val evidence: ConfigurationAcknowledgementEvidence,
    ) : AppliedConfigurationInspection
}

/** Passive coordinator acknowledgement; desired files alone never establish live application. */
object InstalledConfigurationAppliedInspection {
    suspend fun read(kast: Path, userHome: Path, environment: Map<String, String>, desired: ResolvedKastConfiguration,
        workspace: Path? = null): AppliedConfigurationInspection {
        val command = when (val selected = BrokerServiceLaunchCommand.resolveCoordinator(kast, userHome, environment)) {
            is BrokerServiceLaunchCommandResolution.Resolved -> selected.command
            is BrokerServiceLaunchCommandResolution.Rejected -> return unobserved(AppliedConfigurationUnavailable.OWNER_REJECTED)
        }
        val snapshot = when (val observed = InstalledWorkerClient(kast, userHome, environment).status(command)) {
            is CoordinatorStatusRead.Observed -> observed.snapshot
            is CoordinatorStatusRead.Rejected -> return unobserved(when (observed.failure) {
                WorkerControlFailure.UNAVAILABLE, WorkerControlFailure.DEADLINE_EXCEEDED -> AppliedConfigurationUnavailable.COORDINATOR_UNAVAILABLE
                WorkerControlFailure.INVALID_REQUEST, WorkerControlFailure.IDENTITY_REJECTED,
                WorkerControlFailure.SERVICE_IDENTITY_REJECTED, WorkerControlFailure.WORKSPACE_CONFIGURATION_REJECTED,
                WorkerControlFailure.COORDINATOR_IDENTITY_REJECTED, WorkerControlFailure.WORKER_BINDING_IDENTITY_REJECTED,
                WorkerControlFailure.REGISTRATION_REJECTED, WorkerControlFailure.RECEIPT_REJECTED,
                WorkerControlFailure.LIFECYCLE_TRANSITION, WorkerControlFailure.RECOVERY_REQUIRED,
                WorkerControlFailure.CAPACITY_REJECTED, WorkerControlFailure.STARTUP_REJECTED,
                WorkerControlFailure.RETIREMENT_UNPROVEN -> AppliedConfigurationUnavailable.OWNER_REJECTED
            })
        }
        val scope: AppliedConfigurationScope
        val desiredIdentity: String
        val acknowledgedIdentity: String
        val evidence: ConfigurationAcknowledgementEvidence
        if (workspace == null) {
            scope = AppliedConfigurationScope.INSTALLATION
            desiredIdentity = workerLaunchConfigurationIdentity(desired)
            acknowledgedIdentity = snapshot.configurationIdentity
            evidence = ConfigurationAcknowledgementEvidence.Coordinator
        } else {
            val physical = try { workspace.toRealPath() } catch (_: java.io.IOException) {
                return unobserved(AppliedConfigurationUnavailable.OWNER_REJECTED)
            }
            if (physical != workspace) return unobserved(AppliedConfigurationUnavailable.OWNER_REJECTED)
            val canonical = CanonicalBrokerDirectory.admit(physical)
                ?: return unobserved(AppliedConfigurationUnavailable.OWNER_REJECTED)
            val identity = BrokerWorkspaceId.derive(canonical)
            val worker = snapshot.workers.singleOrNull { it.workspaceId == identity.value }
                ?: return unobserved(AppliedConfigurationUnavailable.WORKER_UNOBSERVED)
            scope = AppliedConfigurationScope.WORKSPACE
            desiredIdentity = workerConfigurationIdentity(desired)
            acknowledgedIdentity = worker.configurationIdentity
                ?: return unobserved(AppliedConfigurationUnavailable.CONFIGURATION_UNOBSERVED)
            val requestedHeap = worker.requestedHeapMiB ?: return unobserved(AppliedConfigurationUnavailable.CONFIGURATION_UNOBSERVED)
            val phase = when (WorkerReservationPhase.valueOf(worker.phase)) {
                WorkerReservationPhase.RESERVED -> AppliedWorkerPhase.RESERVED
                WorkerReservationPhase.STARTING -> AppliedWorkerPhase.STARTING
                WorkerReservationPhase.READY -> AppliedWorkerPhase.READY
                WorkerReservationPhase.QUARANTINED_STARTUP -> AppliedWorkerPhase.QUARANTINED_STARTUP
                WorkerReservationPhase.QUARANTINED_RUNTIME -> AppliedWorkerPhase.QUARANTINED_RUNTIME
            }
            evidence = ConfigurationAcknowledgementEvidence.Worker(phase, requestedHeap)
        }
        return if (desiredIdentity == acknowledgedIdentity) AppliedConfigurationInspection.Acknowledged(scope, acknowledgedIdentity, snapshot.serviceGeneration, evidence)
        else AppliedConfigurationInspection.Pending(scope, desiredIdentity, acknowledgedIdentity, snapshot.serviceGeneration, evidence)
    }

    private fun unobserved(reason: AppliedConfigurationUnavailable) = AppliedConfigurationInspection.Unobserved(reason)
}
