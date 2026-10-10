package io.github.amichne.kast.appserver.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class WorkspaceExecutionLaneState {
    @SerialName("idle") IDLE,
    @SerialName("busy") BUSY,
    @SerialName("recovery_required") RECOVERY_REQUIRED,
}

@Serializable
internal enum class WorkspaceExecutionNextAction {
    @SerialName("reobserve_native_authority") REOBSERVE_NATIVE_AUTHORITY,
    @SerialName("observe_provider_settlement") OBSERVE_PROVIDER_SETTLEMENT,
    @SerialName("reconcile_uncertain_mutation") RECONCILE_UNCERTAIN_MUTATION,
}

/** Broker observations describe local execution; they never attest native model validity or settlement. */
@Serializable
internal data class WorkspaceExecutionLaneSnapshot(
    val workspaceId: String,
    val state: WorkspaceExecutionLaneState,
    val queued: Int,
    val blockedBy: WorkspaceExecutionIdentityDocument? = null,
    val nextAction: WorkspaceExecutionNextAction,
)

@Serializable
internal data class WorkspaceExecutionIdentityDocument(
    val connectionId: String,
    val threadId: String,
    val turnId: String,
    val callId: String,
)

internal fun WorkspaceExecutionIdentity.document() = WorkspaceExecutionIdentityDocument(connection.value, thread.value, turn.value, call.value)

@Serializable
internal data class WorkspaceExecutionSnapshot(
    val maximumQueuedPerWorkspace: Int,
    val queueWaitMillis: Long,
    val interactionLimitMillis: Long,
    val lanes: List<WorkspaceExecutionLaneSnapshot>,
    val events: List<WorkspaceExecutionEventSnapshot>,
)

@Serializable
internal data class WorkspaceExecutionEventSnapshot(
    val workspaceId: String,
    val connectionId: String,
    val threadId: String,
    val turnId: String,
    val callId: String,
    val stage: String,
    val outcome: String,
    val elapsedMillis: Long,
    val queueAgeMillis: Long,
    val interactionLimitMillis: Long,
    val remainingMillis: Long,
    val failure: String? = null,
    val certainty: String? = null,
)
