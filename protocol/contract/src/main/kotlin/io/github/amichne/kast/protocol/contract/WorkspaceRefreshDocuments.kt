package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Explicit workspace lifecycle effects; semantic reads never select either effect. */
@Serializable
enum class WorkspaceRefreshEffect {
    FILE_REFRESH,
    GRADLE_MODEL_RELOAD,
}

@Serializable
sealed interface WorkspaceRefreshCommand {
    @Serializable
    @SerialName("request")
    data class Request(val requestId: String, val effect: WorkspaceRefreshEffect) : WorkspaceRefreshCommand

    @Serializable @SerialName("status") data class Status(val requestId: String) : WorkspaceRefreshCommand

    @Serializable
    @SerialName("configure")
    data class Configure(val rule: WorkspaceRefreshRule) : WorkspaceRefreshCommand
}

/** One exact task of the authorized linked build, disabled for every new host. */
@Serializable
sealed interface WorkspaceRefreshRule {
    @Serializable @SerialName("off") data object Off : WorkspaceRefreshRule

    @Serializable
    @SerialName("task_success")
    data class TaskSuccess(val task: String, val effect: WorkspaceRefreshEffect) : WorkspaceRefreshRule
}

@Serializable
sealed interface WorkspaceRefreshResult {
    @Serializable
    @SerialName("pending")
    data class Pending(val requestId: String, val stage: WorkspaceRefreshStage) : WorkspaceRefreshResult

    @Serializable @SerialName("complete") data class Complete(val requestId: String) : WorkspaceRefreshResult

    @Serializable
    @SerialName("failed")
    data class Failed(val requestId: String, val reason: WorkspaceRefreshFailure) : WorkspaceRefreshResult

    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: WorkspaceRefreshFailure) : WorkspaceRefreshResult

    @Serializable
    @SerialName("configured")
    data class Configured(val rule: WorkspaceRefreshRule) : WorkspaceRefreshResult
}

@Serializable
enum class WorkspaceRefreshStage {
    QUEUED,
    EFFECT,
    ADMISSION,
}

@Serializable
enum class WorkspaceRefreshFailure {
    INVALID_REQUEST,
    UNLINKED_BUILD,
    UNSAVED_DOCUMENTS,
    DISPOSED,
    CAPACITY,
    UNKNOWN_REQUEST,
    REQUEST_CONFLICT,
    EFFECT_FAILED,
    CANCELLED,
    DEADLINE_EXCEEDED,
    ADMISSION_REJECTED,
    NEWER_CHANGE,
}

@Serializable
data class WorkspaceRefreshResponse(
    val root: String,
    val host: String,
    val result: WorkspaceRefreshResult,
)

@Serializable
data class WorkspaceRefreshTransportRequest(
    val root: String,
    val document: String,
    val type: String = "WORKSPACE_REFRESH",
)
