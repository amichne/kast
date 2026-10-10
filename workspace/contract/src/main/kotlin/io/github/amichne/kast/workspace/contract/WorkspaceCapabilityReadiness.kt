package io.github.amichne.kast.workspace.contract

/** Requested canonical project selection and owning native endpoint incarnation, never registration metadata. */
data class WorkspaceModelIdentity(val root: CanonicalWorkspaceRoot, val incarnation: IdeReadHostLifetime)

/** Preparation checks the existing imported model; semantic execution still needs its own complete admission. */
enum class WorkspaceCapability {
    MODEL_PREPARATION
}

enum class WorkspaceReadinessReason {
    PROJECT_DISPOSED,
    RETIRED_INCARNATION,
    PROJECT_NOT_OPEN,
    PROJECT_INITIALIZING,
    PROJECT_ROOT_UNAVAILABLE,
    PROJECT_IDENTITY_MISMATCH,
    MODEL_UNAVAILABLE,
    MODEL_INCOMPLETE,
    INDEXING,
    COMPILER_UNAVAILABLE,
    HOST_UNAVAILABLE,
    HOST_INCOMPATIBLE,
    CONFIGURATION_UNAVAILABLE,
    OBSERVATION_FAILED,
    OBSERVATION_CANCELLED,
    EPOCH_UNAVAILABLE,
    NATIVE_WORK,
}

enum class WorkspaceReadinessNextAction {
    REOPEN_PROJECT,
    ATTACH_HOST,
    SELECT_PROJECT,
    OBSERVE_AGAIN,
    REFRESH_MODEL,
    CHECK_CONFIGURATION,
    OBSERVE_SETTLEMENT,
}

sealed interface WorkspaceReadinessDetail {
    data object NoAdditionalEvidence : WorkspaceReadinessDetail

    data class EpochRejected(val failure: ProjectReadEpochObservationFailure) : WorkspaceReadinessDetail

    /** Previous native model evidence remains informational while [currentDetail] describes the current rejection. */
    data class PreviouslyObservedModel(
        val observation: WorkspaceCapabilityReadiness.Ready,
        val currentDetail: WorkspaceReadinessDetail,
    ) : WorkspaceReadinessDetail

    /** The retained observation is informational; settlement and a new current observation are still required. */
    data class UnsettledReads(
        val retainedObservation: WorkspaceCapabilityReadiness,
        val operations: List<WorkspaceReadOperationIdentity>,
    ) : WorkspaceReadinessDetail
}

/**
 * Detached native observation. Ready describes model preparation at [Ready.epoch], not a semantic capability. Retaining
 * this value never makes it current: use and publication must reobserve the original epoch source.
 */
sealed interface WorkspaceCapabilityReadiness {
    val identity: WorkspaceModelIdentity
    val capability: WorkspaceCapability
        get() = WorkspaceCapability.MODEL_PREPARATION

    class Ready
    internal constructor(
        override val identity: WorkspaceModelIdentity,
        val epoch: ProjectReadEpoch<*>,
    ) : WorkspaceCapabilityReadiness

    data class Unavailable(
        override val identity: WorkspaceModelIdentity,
        val reason: WorkspaceReadinessReason,
        val nextAction: WorkspaceReadinessNextAction,
        val detail: WorkspaceReadinessDetail = WorkspaceReadinessDetail.NoAdditionalEvidence,
    ) : WorkspaceCapabilityReadiness

    data class Pending(
        override val identity: WorkspaceModelIdentity,
        val reason: WorkspaceReadinessReason,
        val nextAction: WorkspaceReadinessNextAction,
        val detail: WorkspaceReadinessDetail = WorkspaceReadinessDetail.NoAdditionalEvidence,
    ) : WorkspaceCapabilityReadiness

    data class Blocked(
        override val identity: WorkspaceModelIdentity,
        val reason: WorkspaceReadinessReason,
        val nextAction: WorkspaceReadinessNextAction,
        val detail: WorkspaceReadinessDetail = WorkspaceReadinessDetail.NoAdditionalEvidence,
    ) : WorkspaceCapabilityReadiness
}

/** A pure final-freshness decision over the original opaque native observation domain. */
sealed interface WorkspaceEpochValidation {
    data class Current(val epoch: ProjectReadEpoch<*>) : WorkspaceEpochValidation

    data object Stale : WorkspaceEpochValidation

    data object DifferentIncarnation : WorkspaceEpochValidation

    data class Unavailable(val failure: ProjectReadEpochObservationFailure) : WorkspaceEpochValidation
}

/** No retained snapshot, refresh callback, transport outcome or metadata can replace [current]. */
fun validateWorkspaceEpoch(
    admitted: ProjectReadEpoch<*>,
    current: ProjectReadEpochObservation,
): WorkspaceEpochValidation =
    when (current) {
        is ProjectReadEpochObservation.Observed ->
            when (admitted.relationTo(current.epoch)) {
                ProjectReadEpochRelation.SAME -> WorkspaceEpochValidation.Current(current.epoch)
                ProjectReadEpochRelation.MOVED -> WorkspaceEpochValidation.Stale
                ProjectReadEpochRelation.INCOMPARABLE -> WorkspaceEpochValidation.DifferentIncarnation
            }
        is ProjectReadEpochObservation.Rejected -> WorkspaceEpochValidation.Unavailable(current.failure)
    }
