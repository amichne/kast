package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceInspectionRefreshEffect {
    INCREMENTAL_FILES,
    FORCED_FILES,
    MODEL_RELOAD,
}

@Serializable
enum class WorkspaceInspectionRefreshFailure {
    BUSY,
    UNSAVED_DOCUMENTS,
    UNLINKED_BUILD,
    EFFECT_FAILED,
    ROOT_UNAVAILABLE,
    CANCELLED,
    DISPOSED,
    DEADLINE_EXCEEDED,
}

@Serializable
enum class WorkspaceInspectionRefreshRejection {
    INVALID_REQUEST,
    REQUEST_CONFLICT,
    CAPACITY,
    UNKNOWN_REQUEST,
    DISPOSED,
}

@Serializable
sealed interface WorkspaceInspectionRefreshWaiterIdentity {
    @Serializable @SerialName("request") data class Request(val id: String) : WorkspaceInspectionRefreshWaiterIdentity

    @Serializable @SerialName("read_preparation") data object ReadPreparation : WorkspaceInspectionRefreshWaiterIdentity
}

@Serializable
sealed interface WorkspaceInspectionRefreshStatus {
    @Serializable
    @SerialName("pending")
    data class Pending(val stage: WorkspaceRefreshStage) : WorkspaceInspectionRefreshStatus

    @Serializable @SerialName("complete") data object Complete : WorkspaceInspectionRefreshStatus

    @Serializable
    @SerialName("failed")
    data class Failed(val reason: WorkspaceInspectionRefreshFailure) : WorkspaceInspectionRefreshStatus

    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: WorkspaceInspectionRefreshRejection) : WorkspaceInspectionRefreshStatus
}

@Serializable
data class WorkspaceInspectionRefreshWaiter(
    val identity: WorkspaceInspectionRefreshWaiterIdentity,
    val status: WorkspaceInspectionRefreshStatus,
)

@Serializable
data class WorkspaceInspectionRefreshAttempt(
    val id: Long,
    val effect: WorkspaceInspectionRefreshEffect,
    val stamp: Long,
    val initiator: WorkspaceInspectionRefreshWaiterIdentity,
    val waiters: List<WorkspaceInspectionRefreshWaiter>,
)

/** Waiter failure or retirement never implies settlement of the reported native attempt. */
@Serializable
sealed interface WorkspaceRefreshInspectionDocument {
    @Serializable @SerialName("unknown") data object Unknown : WorkspaceRefreshInspectionDocument

    @Serializable @SerialName("idle") data object Idle : WorkspaceRefreshInspectionDocument

    @Serializable
    @SerialName("retired")
    data class Retired(
        val unsettled: List<WorkspaceInspectionRefreshAttempt>,
        val nextAction: WorkspaceInspectionNextAction = WorkspaceInspectionNextAction.ATTACH_HOST,
    ) : WorkspaceRefreshInspectionDocument

    @Serializable
    @SerialName("running")
    data class Running(
        val active: WorkspaceInspectionRefreshAttempt,
        val queued: List<WorkspaceInspectionRefreshAttempt>,
        val nextAction: WorkspaceInspectionNextAction = WorkspaceInspectionNextAction.OBSERVE_SETTLEMENT,
    ) : WorkspaceRefreshInspectionDocument

    @Serializable
    @SerialName("awaiting_admission")
    data class AwaitingAdmission(
        val waiters: List<WorkspaceInspectionRefreshWaiter>,
        val nextAction: WorkspaceInspectionNextAction = WorkspaceInspectionNextAction.OBSERVE_AGAIN,
    ) : WorkspaceRefreshInspectionDocument
}
