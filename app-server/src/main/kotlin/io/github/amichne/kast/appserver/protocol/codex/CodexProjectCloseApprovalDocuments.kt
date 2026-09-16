package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal object CodexProjectCloseApprovalProjection {
    fun qualificationWitnesses(): Map<CodexOwnedSchema, JsonElement> {
        val json = Json { encodeDefaults = true }
        val item = CloseItem("close-1", "kast workspace lifecycle", "/workspace", CloseItemStatus.IN_PROGRESS)
        return mapOf(
            CodexOwnedSchema.ITEM_STARTED_NOTIFICATION to
                json.encodeToJsonElement(CloseStarted("thread", "turn", 1, item)),
            CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION to
                json.encodeToJsonElement(
                    CloseCompleted("thread", "turn", 2, item.copy(status = CloseItemStatus.COMPLETED))
                ),
            CodexOwnedSchema.COMMAND_EXECUTION_REQUEST_APPROVAL_PARAMS to
                json.encodeToJsonElement(
                    CloseApprovalParams(
                        "thread",
                        "turn",
                        "close-1",
                        1,
                        item.command,
                        item.cwd,
                        "Close this exact project incarnation",
                    )
                ),
            CodexOwnedSchema.COMMAND_EXECUTION_REQUEST_APPROVAL_RESPONSE to
                json.encodeToJsonElement(CloseApprovalResponse(CloseDecision.ACCEPT)),
            CodexOwnedSchema.SERVER_REQUEST_RESOLVED_NOTIFICATION to
                json.encodeToJsonElement(CloseResolved("thread", "close-1")),
        )
    }
}

@Serializable
internal enum class CloseDecision {
    @SerialName("accept") ACCEPT,
    @SerialName("acceptForSession") SESSION,
    @SerialName("decline") DECLINE,
    @SerialName("cancel") CANCEL,
}

@Serializable
internal enum class CloseItemStatus {
    @SerialName("inProgress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("declined") DECLINED,
    @SerialName("failed") FAILED,
}

@Serializable internal data class CloseApprovalResponse(val decision: CloseDecision)

@Serializable
internal data class CloseItem(
    val id: String,
    val command: String,
    val cwd: String,
    val status: CloseItemStatus,
    val type: String = "commandExecution",
    val commandActions: List<CloseCommandAction> = emptyList(),
)

@Serializable internal data class CloseCommandAction(val command: String, val type: String = "unknown")

@Serializable
internal data class CloseStarted(val threadId: String, val turnId: String, val startedAtMs: Long, val item: CloseItem)

@Serializable
internal data class CloseCompleted(
    val threadId: String,
    val turnId: String,
    val completedAtMs: Long,
    val item: CloseItem,
)

@Serializable
internal data class CloseApprovalParams(
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val startedAtMs: Long,
    val command: String,
    val cwd: String,
    val reason: String,
)

@Serializable
internal data class CloseApprovalEnvelope(
    val id: String,
    val params: CloseApprovalParams,
    val method: String = "item/commandExecution/requestApproval",
)

@Serializable internal data class CloseStartedEnvelope(val params: CloseStarted, val method: String = "item/started")

@Serializable
internal data class CloseCompletedEnvelope(val params: CloseCompleted, val method: String = "item/completed")

@Serializable internal data class CloseResolved(val threadId: String, val requestId: String)

@Serializable
internal data class CloseResolvedEnvelope(val params: CloseResolved, val method: String = "serverRequest/resolved")
