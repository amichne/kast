package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.protocol.codex.WorkspaceDemandFailureDocument
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The request ID is the protocol-defined opaque JSON-RPC correlation value. */
internal fun planApprovalFailure(request: JsonObject, failure: HostedPlanApprovalRejection): String {
    val document =
        when (failure) {
            is HostedPlanApprovalFailure -> PlanApprovalFailureDocument("PLAN_APPROVAL_${failure.name}")
            is HostedPlanApprovalRejection.Workspace ->
                PlanApprovalFailureDocument(
                    "WORKSPACE_PREPARATION_REJECTED",
                    workspace = WorkspaceDemandFailureDocument.from(failure.cause),
                )
        }
    return approvalJson.encodeToString(
        ApprovalFailureReply(
            request["id"] ?: JsonNull,
            ApprovalFailureResult(listOf(ApprovalFailureText(approvalJson.encodeToString(document)))),
        )
    )
}

private val approvalJson = Json { encodeDefaults = true }

@Serializable
@OptIn(ExperimentalSerializationApi::class)
private data class PlanApprovalFailureDocument(
    val failure: String,
    val status: String = "rejected",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val workspace: WorkspaceDemandFailureDocument? = null,
)

@Serializable private data class ApprovalFailureReply(val id: JsonElement, val result: ApprovalFailureResult)

@Serializable
private data class ApprovalFailureResult(val contentItems: List<ApprovalFailureText>, val success: Boolean = false)

@Serializable private data class ApprovalFailureText(val text: String, val type: String = "inputText")
