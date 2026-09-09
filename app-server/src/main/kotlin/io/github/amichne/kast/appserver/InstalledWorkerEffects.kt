package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

/** Startup choices are finite data. Interactive consent remains at the requesting frontend. */
sealed interface InstalledWorkerStartup {
    val ideHome: Path?
    data class Reuse(override val ideHome: Path? = null) : InstalledWorkerStartup
    data class Rebuild(override val ideHome: Path? = null) : InstalledWorkerStartup
    data class Seed(override val ideHome: Path?, val sourceSystem: Path?, val consent: WorkerSeedConsentSelection = WorkerSeedConsentSelection.PREGRANTED) : InstalledWorkerStartup
}
class InstalledWorkerStartRequest internal constructor(
    val root: Path,
    val heap: IndexerHeapSize,
    val startup: InstalledWorkerStartup,
    val configuration: io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration,
    val consentAuthority: WorkerSeedConsentAuthority = WorkerSeedConsentAuthority.Unavailable,
)

/** Effect adapter may construct this only after exact process, reachable socket and bootstrap correlation. */
class InstalledWorkerEndpoint private constructor(
    val root: Path,
    val runtimeId: SemanticRuntimeId,
    val socket: Path,
    val attempt: SemanticRuntimeBootstrapAttemptId,
) {
    companion object {
        fun admit(root: Path, runtimeId: SemanticRuntimeId, socket: Path, attempt: SemanticRuntimeBootstrapAttemptId): Refinement<InstalledWorkerEndpoint, WorkerControlFailure> =
            if (root.isAbsolute && root.normalize() == root && socket.isAbsolute && socket.normalize() == socket)
                Refinement.Refined(InstalledWorkerEndpoint(root, runtimeId, socket, attempt))
            else Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
    }
}

@kotlinx.serialization.Serializable
enum class WorkerControlFailure {
    UNAVAILABLE, INVALID_REQUEST, IDENTITY_REJECTED, SERVICE_IDENTITY_REJECTED,
    WORKSPACE_CONFIGURATION_REJECTED, COORDINATOR_IDENTITY_REJECTED,
    WORKER_BINDING_IDENTITY_REJECTED, REGISTRATION_REJECTED, RECEIPT_REJECTED,
    LIFECYCLE_TRANSITION, RECOVERY_REQUIRED, CAPACITY_REJECTED, STARTUP_REJECTED,
    DEADLINE_EXCEEDED, RETIREMENT_UNPROVEN,
}
sealed interface InstalledWorkerStart {
    data class Ready(val endpoint: InstalledWorkerEndpoint, val binding: WorkerRouteBinding = WorkerRouteBinding.EffectBoundary) : InstalledWorkerStart
    data class Rejected(val failure: WorkerControlFailure) : InstalledWorkerStart
}
enum class InstalledWorkerObservation { EXACT_READY, UNPROVEN }
enum class InstalledWorkerRetirement { EXACT_RETIRED, UNPROVEN }

/** The existing installed launcher and exact process authority implement this effect boundary. */
interface InstalledWorkerEffects {
    suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart
    suspend fun observe(route: InstalledWorkerEndpoint): InstalledWorkerObservation
    suspend fun stop(route: InstalledWorkerEndpoint): InstalledWorkerRetirement
    suspend fun retireUnpublished(root: Path): InstalledWorkerRetirement = InstalledWorkerRetirement.UNPROVEN
    suspend fun retireUnreserved(root: Path): InstalledWorkerRetirement = InstalledWorkerRetirement.UNPROVEN

    data object Unavailable : InstalledWorkerEffects {
        override suspend fun start(request: InstalledWorkerStartRequest) = InstalledWorkerStart.Rejected(WorkerControlFailure.UNAVAILABLE)
        override suspend fun observe(route: InstalledWorkerEndpoint) = InstalledWorkerObservation.UNPROVEN
        override suspend fun stop(route: InstalledWorkerEndpoint) = InstalledWorkerRetirement.UNPROVEN
    }
}

/** Owner and selected configuration proof retained across runtime readiness and semantic dispatch. */
sealed interface WorkerRouteBinding {
    data object EffectBoundary : WorkerRouteBinding
    class Installation private constructor(
        val installationId: String,
        val stateEpoch: java.util.UUID,
        val serviceGeneration: java.util.UUID,
        val configurationIdentity: String,
        val workspaceRevision: Long,
    ) : WorkerRouteBinding {
        companion object {
            fun admit(installationId: String, stateEpoch: String, serviceGeneration: String, configurationIdentity: String, workspaceRevision: Long): Refinement<Installation,WorkerControlFailure> = try {
                val epoch = java.util.UUID.fromString(stateEpoch)
                val generation = java.util.UUID.fromString(serviceGeneration)
                if (!installationId.matches(Regex("[A-Za-z0-9._:-]{1,256}")) || epoch.toString() != stateEpoch || generation.toString() != serviceGeneration ||
                    !configurationIdentity.matches(Regex("[0-9a-f]{64}")) || workspaceRevision < 1) Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
                else Refinement.Refined(Installation(installationId,epoch,generation,configurationIdentity,workspaceRevision))
            } catch (_: IllegalArgumentException) { Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED) }
        }
    }
}

sealed interface InstalledWorkerStop {
    data class Stopped(val root: Path, val binding: WorkerRouteBinding.Installation) : InstalledWorkerStop
    data class Rejected(val failure: WorkerControlFailure) : InstalledWorkerStop
}
