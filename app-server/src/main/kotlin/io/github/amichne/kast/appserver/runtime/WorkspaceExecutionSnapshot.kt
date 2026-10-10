package io.github.amichne.kast.appserver.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

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

internal fun WorkspaceExecutionIdentity.document() =
    WorkspaceExecutionIdentityDocument(connection.value, thread.value, turn.value, call.value)

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

internal fun serializeWorkspaceExecutionSnapshot(
    policy: WorkspaceExecutionPolicy,
    lanes: List<WorkspaceExecutionLaneSnapshot>,
    events: Iterable<WorkspaceExecutionEvent>,
): JsonObject =
    snapshotJson
        .encodeToJsonElement(
            WorkspaceExecutionSnapshot(
                policy.maximumQueued,
                policy.queueWait.value,
                policy.interaction.value,
                lanes,
                events.map(WorkspaceExecutionEvent::document),
            )
        )
        .jsonObject

private fun WorkspaceExecutionEvent.document() =
    WorkspaceExecutionEventSnapshot(
        request.workspace.value,
        request.connection.value,
        request.thread.value,
        request.turn.value,
        request.call.value,
        stage.name.lowercase(),
        outcome.name.lowercase(),
        elapsed.inWholeMilliseconds,
        queued.inWholeMilliseconds,
        interactionLimit.value,
        (interactionLimit.value - elapsed.inWholeMilliseconds).coerceAtLeast(0),
        failure?.name,
        failure?.certainty?.name?.lowercase(),
    )

private val snapshotJson = Json {
    encodeDefaults = true
    explicitNulls = false
}
