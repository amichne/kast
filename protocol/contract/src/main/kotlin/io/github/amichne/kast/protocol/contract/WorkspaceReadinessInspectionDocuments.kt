package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Inspection is observational. An opaque native epoch remains confined to its original IntelliJ owner. */
@Serializable
data class WorkspaceInspectionModelIdentity(val root: String, val incarnation: String)

@Serializable
enum class WorkspaceInspectionCapability { MODEL_PREPARATION }

@Serializable
enum class WorkspaceInspectionEpochEvidence { OBSERVED_OPAQUE_EPOCH }

@Serializable
enum class WorkspaceInspectionReadinessReason {
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
    EPOCH_UNAVAILABLE,
    NATIVE_WORK,
}

@Serializable
enum class WorkspaceInspectionNextAction {
    REOPEN_PROJECT,
    ATTACH_HOST,
    SELECT_PROJECT,
    OBSERVE_AGAIN,
    REFRESH_MODEL,
    CHECK_CONFIGURATION,
    OBSERVE_SETTLEMENT,
}

@Serializable
sealed interface WorkspaceInspectionReadOperation {
    @Serializable @SerialName("opaque") data object Opaque : WorkspaceInspectionReadOperation

    @Serializable @SerialName("traced") data class Traced(val trace: String) : WorkspaceInspectionReadOperation
}

@Serializable
sealed interface WorkspaceInspectionRetainedModel {
    @Serializable @SerialName("unknown") data object Unknown : WorkspaceInspectionRetainedModel

    @Serializable
    @SerialName("observed")
    data class Observed(
        val identity: WorkspaceInspectionModelIdentity,
        val epoch: WorkspaceInspectionEpochEvidence = WorkspaceInspectionEpochEvidence.OBSERVED_OPAQUE_EPOCH,
    ) : WorkspaceInspectionRetainedModel

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val reason: WorkspaceInspectionReadinessReason,
        val nextAction: WorkspaceInspectionNextAction,
        val epochRejection: WorkspaceInspectionEpochRejection = WorkspaceInspectionEpochRejection.Unobserved,
    ) : WorkspaceInspectionRetainedModel
}

@Serializable
enum class WorkspaceInspectionEpochFailure {
    WRONG_THREAD, PROJECT_DISPOSED, PROJECT_NOT_OPEN, PROJECT_NOT_INITIALIZED,
    PROJECT_ROOT_UNAVAILABLE, PROJECT_ROOT_MALFORMED, DUMB_MODE, GRADLE_MODEL_UNAVAILABLE,
    GRADLE_MODEL_INCOMPLETE, GRADLE_MODEL_AMBIGUOUS, GRADLE_ROOT_UNAVAILABLE, GRADLE_ROOT_MALFORMED,
    IMPORT_TIMESTAMPS_INCOHERENT, VFS_BATCH_LIMIT_EXCEEDED, VFS_PATH_MALFORMED, SIGNAL_EXHAUSTED, READ_PREEMPTED,
}

@Serializable
enum class WorkspaceInspectionEpochStage {
    THREAD, DISPOSAL, OPEN, INITIALIZATION, PROJECT_ROOT, PROJECT_MODEL, PSI, VFS, ROOT_MODEL, DUMB_MODE,
}

@Serializable
sealed interface WorkspaceInspectionEpochRejection {
    @Serializable @SerialName("unobserved") data object Unobserved : WorkspaceInspectionEpochRejection
    @Serializable @SerialName("rejected") data class Rejected(val cause: WorkspaceInspectionEpochFailure) : WorkspaceInspectionEpochRejection
    @Serializable @SerialName("observation_failed") data class ObservationFailed(val stage: WorkspaceInspectionEpochStage) : WorkspaceInspectionEpochRejection
}

@Serializable
data class WorkspaceInspectionObstruction(
    val reason: WorkspaceInspectionReadinessReason,
    val nextAction: WorkspaceInspectionNextAction,
    val retainedModel: WorkspaceInspectionRetainedModel = WorkspaceInspectionRetainedModel.Unknown,
    val activeReads: List<WorkspaceInspectionReadOperation> = emptyList(),
    val epochRejection: WorkspaceInspectionEpochRejection = WorkspaceInspectionEpochRejection.Unobserved,
)

/** Each variant reports current native preparation evidence and grants no semantic execution authority. */
@Serializable
sealed interface WorkspaceReadinessInspectionDocument {
    @Serializable @SerialName("unknown") data object Unknown : WorkspaceReadinessInspectionDocument

    @Serializable
    @SerialName("ready")
    data class Ready(
        val identity: WorkspaceInspectionModelIdentity,
        val capability: WorkspaceInspectionCapability = WorkspaceInspectionCapability.MODEL_PREPARATION,
        val epoch: WorkspaceInspectionEpochEvidence = WorkspaceInspectionEpochEvidence.OBSERVED_OPAQUE_EPOCH,
    ) : WorkspaceReadinessInspectionDocument

    @Serializable
    @SerialName("unavailable")
    data class Unavailable(
        val identity: WorkspaceInspectionModelIdentity,
        val obstruction: WorkspaceInspectionObstruction,
        val capability: WorkspaceInspectionCapability = WorkspaceInspectionCapability.MODEL_PREPARATION,
    ) : WorkspaceReadinessInspectionDocument

    @Serializable
    @SerialName("pending")
    data class Pending(
        val identity: WorkspaceInspectionModelIdentity,
        val obstruction: WorkspaceInspectionObstruction,
        val capability: WorkspaceInspectionCapability = WorkspaceInspectionCapability.MODEL_PREPARATION,
    ) : WorkspaceReadinessInspectionDocument

    @Serializable
    @SerialName("blocked")
    data class Blocked(
        val identity: WorkspaceInspectionModelIdentity,
        val obstruction: WorkspaceInspectionObstruction,
        val capability: WorkspaceInspectionCapability = WorkspaceInspectionCapability.MODEL_PREPARATION,
    ) : WorkspaceReadinessInspectionDocument
}
