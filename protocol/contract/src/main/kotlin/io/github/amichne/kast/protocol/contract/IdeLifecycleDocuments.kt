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
    data class Open(
        val host: String,
        val requestId: String,
        val client: String,
        val root: String,
        val preparationMillis: Long = WorkspacePreparationBudget.limit.value,
    ) : IdeLifecycleCommand

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
    @SerialName("configure_sync")
    data class ConfigureSync(
        val requestId: String,
        val client: String,
        val target: IdeProjectTarget,
        val rule: WorkspaceRefreshRule,
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
        val confirmation: ProjectCloseApprovalPayload,
    ) : IdeLifecycleCommand

    @Serializable @SerialName("status") data class Status(val host: String, val requestId: String) : IdeLifecycleCommand

    @Serializable
    @SerialName("settle_host")
    data class SettleHost(val host: String, val transaction: String) : IdeLifecycleCommand

    @Serializable
    @SerialName("resume_host")
    data class ResumeHost(val host: String, val transaction: String) : IdeLifecycleCommand

    @Serializable
    @SerialName("stop_host")
    data class StopHost(val host: String, val transaction: String) : IdeLifecycleCommand
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
    MODEL_RELOAD_REQUIRED,
    HOST_UNAVAILABLE,
    EXCHANGE_DEADLINE_EXCEEDED,
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
    COMPATIBILITY_REJECTED,
    UNSUPPORTED_PLATFORM_LINE,
    UNSUPPORTED_HOST_MODE,
    DEADLINE_EXCEEDED,
    HOST_FENCED,
    MUTATION_RECOVERY_REQUIRED,
}

@Serializable
data class IdeProjectDescription(
    val target: IdeProjectTarget,
    val ownership: IdeProjectOwnership,
    val users: Int,
    val readiness: WorkspaceReadinessInspectionDocument = WorkspaceReadinessInspectionDocument.Unknown,
    val refresh: WorkspaceRefreshInspectionDocument = WorkspaceRefreshInspectionDocument.Unknown,
)

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
        val compatibility: HostedCompatibilityDocument,
        val protocol: Int = 1,
        val background: IdeBackgroundPresentation = IdeBackgroundPresentation.BEST_EFFORT,
        val capabilities: Set<IdeLifecycleCapabilityName> = IdeLifecycleCapabilityName.entries.toSet(),
        val runtimeIdentity: IdeHostRuntimeIdentity? = null,
    ) : IdeLifecycleResult

    @Serializable
    @SerialName("host_settling")
    data class HostSettling(val host: String, val transaction: String) : IdeLifecycleResult

    @Serializable
    @SerialName("host_settled")
    data class HostSettled(val host: String, val transaction: String, val runtimeIdentity: IdeHostRuntimeIdentity) :
        IdeLifecycleResult

    @Serializable
    @SerialName("host_resumed")
    data class HostResumed(val host: String, val transaction: String) : IdeLifecycleResult

    @Serializable
    @SerialName("host_stop_requested")
    data class HostStopRequested(
        val host: String,
        val transaction: String,
        val runtimeIdentity: IdeHostRuntimeIdentity,
    ) : IdeLifecycleResult

    @Serializable
    @SerialName("pending")
    data class Pending(val requestId: String, val stage: IdeLifecycleStage, val host: String) : IdeLifecycleResult

    @Serializable @SerialName("opened") data class Opened(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable @SerialName("presented") data class Presented(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable @SerialName("synced") data class Synced(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable
    @SerialName("configured")
    data class Configured(val target: IdeProjectTarget, val rule: WorkspaceRefreshRule) : IdeLifecycleResult

    @Serializable @SerialName("released") data class Released(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable @SerialName("closed") data class Closed(val target: IdeProjectTarget) : IdeLifecycleResult

    @Serializable
    @SerialName("blocked")
    data class Blocked(
        val reason: IdeLifecycleFailure,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val preparation: WorkspacePreparationTiming? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val exchange: IdeLifecycleExchangeFailure? = null,
    ) : IdeLifecycleResult, IdeLifecycleRejection
}

@Serializable
enum class IdeLifecycleQualification : OperationQualification {
    PENDING
}

interface IdeLifecycleCapability : io.github.amichne.kast.kernel.CapabilityMarker

/** Exact-target controller response retained for local endpoint correlation. */
@Serializable
data class ProjectCloseApprovalPayload(
    val target: IdeProjectTarget,
    val requestId: String,
    val client: String,
    val threadId: String,
    val turnId: String,
    val callId: String,
)

@Serializable
data class ApprovedProjectCloseInvocation(
    val arguments: WorkspaceLifecycleRequest.RequestUserClose,
    val confirmation: ProjectCloseApprovalPayload,
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
    CONFIGURE_SYNC,
    RELEASE,
    CLOSE,
    STATUS,
    EXACT_USER_CLOSE,
    HOST_SETTLEMENT,
    HOST_RUNTIME_IDENTITY,
    HOST_NORMAL_STOP,
}

@Serializable data class IdeHostBuildIdentity(val version: String, val sourceRevision: String, val sourceTree: String)

/** The loaded plugin captures these facts once per application-level Host lifetime. */
@Serializable
data class IdeHostRuntimeIdentity(
    val ideaHome: String,
    val profile: String,
    val pid: Long,
    val processStartEpochMillis: Long,
    val hostLifetime: String,
    val buildIdentity: IdeHostBuildIdentity,
    val autoReload: HostAutoReload = HostAutoReload.DISABLED,
)

@Serializable
enum class HostAutoReload {
    ENABLED,
    DISABLED,
}

@Serializable
data class IdeHostApplicationAdvertisement(
    val runtimeIdentity: IdeHostRuntimeIdentity,
    val compatibility: HostedCompatibilityDocument,
)
