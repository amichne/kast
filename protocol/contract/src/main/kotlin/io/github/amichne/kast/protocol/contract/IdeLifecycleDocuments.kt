package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact application and project incarnations; roots alone never authorize effects. */
@Serializable data class IdeProjectTarget(val host: String, val project: String, val root: String)

@Serializable
sealed interface IdeLifecycleCommand : OperationRequest {
    @Serializable @SerialName("inspect") data object Inspect : IdeLifecycleCommand

    @Serializable
    @SerialName("open")
    data class Open(val host: String, val requestId: String, val client: String, val root: String) : IdeLifecycleCommand

    @Serializable
    @SerialName("present")
    data class Present(val requestId: String, val client: String, val target: IdeProjectTarget) : IdeLifecycleCommand

    @Serializable
    @SerialName("sync")
    data class Sync(
        val requestId: String,
        val client: String,
        val target: IdeProjectTarget,
        val effect: WorkspaceRefreshEffect,
    ) : IdeLifecycleCommand

    @Serializable
    @SerialName("release")
    data class Release(val requestId: String, val client: String, val target: IdeProjectTarget) : IdeLifecycleCommand

    @Serializable
    @SerialName("close")
    data class Close(val requestId: String, val client: String, val target: IdeProjectTarget) : IdeLifecycleCommand

    @Serializable
    @SerialName("authorized_close")
    data class AuthorizedClose(
        val requestId: String,
        val client: String,
        val target: IdeProjectTarget,
        val assertion: String,
    ) : IdeLifecycleCommand

    @Serializable @SerialName("status") data class Status(val host: String, val requestId: String) : IdeLifecycleCommand
}

@Serializable
enum class IdeProjectOwnership {
    BORROWED,
    MANAGED,
    PRESENTED,
}

@Serializable
enum class IdeLifecycleStage {
    OPENING,
    IMPORTING,
    ADMISSION,
    PRESENTING,
    CLOSING,
}

@Serializable
enum class IdeLifecycleFailure : OperationRejection {
    HOST_UNAVAILABLE,
    PLUGIN_UNAVAILABLE,
    SELECTED_IDE_UNAVAILABLE,
    LAUNCH_METADATA_UNAVAILABLE,
    LAUNCH_METADATA_INVALID,
    LAUNCHER_MISSING,
    LAUNCHER_AMBIGUOUS,
    LAUNCHER_INVALID,
    LAUNCH_EXECUTABLE_UNAVAILABLE,
    LAUNCH_FAILED,
    INVALID_REQUEST,
    WRONG_HOST,
    STALE_PROJECT,
    UNKNOWN_OPERATION,
    REQUEST_CONFLICT,
    CAPACITY,
    PROJECT_BUSY,
    OTHER_CLIENTS,
    USER_AUTHORIZATION_REQUIRED,
    UNSAVED_DOCUMENTS,
    TRUST_REQUIRED,
    UNLINKED_BUILD,
    IMPORT_FAILED,
    CANCELLED,
    DISPOSED,
    SHUTDOWN,
    OPEN_FAILED,
    CLOSE_VETOED,
    CLOSE_RETIREMENT_PENDING,
    PLATFORM_UNAVAILABLE,
    HOST_IDENTITY_MISMATCH,
    UNSUPPORTED_PROTOCOL,
    CAPABILITY_MISMATCH,
    UNSUPPORTED_PLATFORM_LINE,
    UNSUPPORTED_HOST_MODE,
    DEADLINE_EXCEEDED,
}

@Serializable
data class IdeProjectDescription(val target: IdeProjectTarget, val ownership: IdeProjectOwnership, val users: Int)

@Serializable sealed interface IdeLifecycleRejection

@Serializable
sealed interface IdeLifecycleResult : OperationResult {
    @Serializable
    @SerialName("inspected")
    data class Inspected(
        val host: String,
        val home: String,
        val build: String,
        val projects: List<IdeProjectDescription>,
        val protocol: Int = 1,
        val background: IdeBackgroundPresentation = IdeBackgroundPresentation.BEST_EFFORT,
        val capabilities: Set<IdeLifecycleCapabilityName> = IdeLifecycleCapabilityName.entries.toSet(),
    ) : IdeLifecycleResult

    @Serializable
    @SerialName("pending")
    data class Pending(val requestId: String, val stage: IdeLifecycleStage, val host: String) : IdeLifecycleResult

    @Serializable @SerialName("opened") data class Opened(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable @SerialName("presented") data class Presented(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable @SerialName("synced") data class Synced(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable @SerialName("released") data class Released(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable @SerialName("closed") data class Closed(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable
    @SerialName("blocked")
    data class Blocked(val reason: IdeLifecycleFailure) : IdeLifecycleResult, IdeLifecycleRejection
}

@Serializable
enum class IdeLifecycleQualification : OperationQualification {
    PENDING
}

interface IdeLifecycleCapability : io.github.amichne.kast.kernel.CapabilityMarker

/** Signed only after an exact-target controller response; never supplied by public model arguments. */
@Serializable
data class ProjectCloseApprovalPayload(
    val target: IdeProjectTarget,
    val requestId: String,
    val client: String,
    val threadId: String,
    val turnId: String,
    val callId: String,
    val purpose: String = "kast-project-close-v1",
)

@Serializable
data class ApprovedProjectCloseInvocation(
    val arguments: WorkspaceLifecycleRequest.RequestUserClose,
    val approval: String,
)

@Serializable
enum class IdeBackgroundPresentation {
    @SerialName("best_effort") BEST_EFFORT
}

@Serializable
enum class IdeLifecycleCapabilityName {
    INSPECT,
    OPEN,
    PRESENT,
    SYNC,
    RELEASE,
    CLOSE,
    STATUS,
    EXACT_USER_CLOSE,
}
