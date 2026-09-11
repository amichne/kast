package io.github.amichne.kast.appserver.protocol.codex

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
internal enum class PlanApprovalItemCompletion {
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("declined") DECLINED,
}

@Serializable
internal enum class PlanApprovalItemStarted {
    @SerialName("inProgress") IN_PROGRESS
}

@Serializable
internal enum class PlanApprovalDecisionWitness {
    @SerialName("accept") ACCEPT,
    @SerialName("acceptForSession") ACCEPT_FOR_SESSION,
    @SerialName("decline") DECLINE,
    @SerialName("cancel") CANCEL,
}

/** Output DTOs retain the fixed Codex shapes until schema admission or adapter emission. */
@OptIn(ExperimentalSerializationApi::class)
internal object CodexPlanApprovalDocuments {
    @Serializable
    data class PreviewItem<S>(val id: String, val status: S, val changes: List<FileUpdate>) {
        @EncodeDefault val type: String = "fileChange"
    }

    @Serializable
    data class FileUpdate(val path: String, val diff: String) {
        @EncodeDefault val kind: UpdateKind = UpdateKind
    }

    @Serializable
    data object UpdateKind {
        @EncodeDefault val type: String = "update"
    }

    @Serializable
    data class Started(
        val threadId: String,
        val turnId: String,
        val startedAtMs: Long,
        val item: PreviewItem<PlanApprovalItemStarted>,
    ) {
        fun completionTemplate(status: PlanApprovalItemCompletion): Completed =
            Completed(
                threadId = threadId,
                turnId = turnId,
                completedAtMs = startedAtMs,
                item = PreviewItem(item.id, status, item.changes),
            )
    }

    @Serializable
    data class Completed(
        val threadId: String,
        val turnId: String,
        val completedAtMs: Long,
        val item: PreviewItem<PlanApprovalItemCompletion>,
    )

    @Serializable
    data class ApprovalRequest(
        val threadId: String,
        val turnId: String,
        val itemId: String,
        val startedAtMs: Long,
        val reason: String,
    )

    @Serializable data class Resolved(val threadId: String, val requestId: String)

    @Serializable data class Decision(val decision: PlanApprovalDecisionWitness)

    @Serializable
    data class StartedNotification(val params: Started) {
        @EncodeDefault val method: String = "item/started"
    }

    @Serializable
    data class CompletedNotification(val params: Completed) {
        @EncodeDefault val method: String = "item/completed"
    }

    @Serializable
    data class ApprovalRequestEnvelope(val id: String, val params: ApprovalRequest) {
        @EncodeDefault val method: String = "item/fileChange/requestApproval"
    }

    @Serializable
    data class ResolvedNotification(val params: Resolved) {
        @EncodeDefault val method: String = "serverRequest/resolved"
    }

    inline fun <reified T> encode(document: T): JsonObject = Json.encodeToJsonElement(document).jsonObject
}
