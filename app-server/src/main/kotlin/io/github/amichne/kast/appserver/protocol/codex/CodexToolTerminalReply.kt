package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.ToolPresentation
import io.github.amichne.kast.appserver.runtime.WorkspaceExecutionLaneSnapshot
import io.github.amichne.kast.appserver.schema.canonicalJson
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal fun codexToolFailurePresentation(failure: CodexToolTerminalFailure): ToolPresentation =
    ToolPresentation.text(terminalJson.encodeToString(CodexToolRejectionDocument(failure)), success = false)

internal fun codexToolCancellationPresentation(): ToolPresentation =
    ToolPresentation.text(terminalJson.encodeToString(CodexToolCancellationDocument()), success = false)

/** The byte guard evaluates the actual upstream response including JSON text escaping. */
internal fun encodeBoundedDynamicToolResult(presentation: ToolPresentation, maximumBytes: Int): JsonObject {
    val result = presentation.encodeDynamicToolResult()
    return if (canonicalJson(result).toByteArray(Charsets.UTF_8).size <= maximumBytes) result
    else
        codexToolFailurePresentation(CodexToolTerminalFailure.BROKER_OVERLOADED_MAXIMUM_TOOL_RESULT_BYTES)
            .encodeDynamicToolResult()
}

private fun ToolPresentation.encodeDynamicToolResult(): JsonObject =
    terminalJson
        .encodeToJsonElement(
            CodexDynamicToolResultDocument(success, content.map { CodexDynamicToolTextDocument(it.text) })
        )
        .jsonObject

private val terminalJson = Json { encodeDefaults = true }

@Serializable
private data class CodexDynamicToolResultDocument(
    val success: Boolean,
    val contentItems: List<CodexDynamicToolTextDocument>,
)

@Serializable private data class CodexDynamicToolTextDocument(val text: String, val type: String = "inputText")

@Serializable
private data class CodexToolRejectionDocument(
    val failure: CodexToolTerminalFailure,
    val status: CodexRejectedStatus = CodexRejectedStatus.REJECTED,
)

@Serializable
private enum class CodexRejectedStatus {
    @SerialName("rejected") REJECTED
}

@Serializable
private data class CodexToolCancellationDocument(
    val status: CodexCancelledStatus = CodexCancelledStatus.CANCELLED,
    val effect: CodexUncertainEffect = CodexUncertainEffect.UNCERTAIN,
)

@Serializable
private enum class CodexCancelledStatus {
    @SerialName("cancelled") CANCELLED
}

@Serializable
private enum class CodexUncertainEffect {
    @SerialName("uncertain") UNCERTAIN
}

internal fun codexThreadStoreFailurePresentation(
    failure: io.github.amichne.kast.appserver.protocol.ThreadCatalogStoreFailure
): ToolPresentation =
    ToolPresentation.text(terminalJson.encodeToString(CodexThreadStoreRejectionDocument(failure)), success = false)

@Serializable
private data class CodexThreadStoreRejectionDocument(
    val failure: io.github.amichne.kast.appserver.protocol.ThreadCatalogStoreFailure,
    val status: CodexRejectedStatus = CodexRejectedStatus.REJECTED,
)

internal enum class WorkspaceInspectionProjectionFailure {
    INSPECTION_REPLY_REJECTED,
    INSPECTION_OUTPUT_LIMIT,
}

/** Adds local fence/operation evidence to native inspection without turning broker state into native authority. */
internal fun appendWorkspaceInspection(
    reply: ProtocolRouting.ReplyUpstream,
    observation: WorkspaceExecutionLaneSnapshot,
    maximumBytes: Int,
    maximumResultBytes: Int = maximumBytes,
): Refinement<ProtocolRouting.ReplyUpstream, WorkspaceInspectionProjectionFailure> {
    val received =
        try {
            terminalJson.decodeFromString<WorkspaceInspectionReplyDocument>(reply.message)
        } catch (_: SerializationException) {
            return Refinement.Rejected(WorkspaceInspectionProjectionFailure.INSPECTION_REPLY_REJECTED)
        }
    val local =
        CodexDynamicToolTextDocument(terminalJson.encodeToString(WorkspaceExecutionInspectionDocument(observation)))
    val result = received.result.copy(contentItems = received.result.contentItems + local)
    if (terminalJson.encodeToString(result).toByteArray(Charsets.UTF_8).size > maximumResultBytes)
        return Refinement.Rejected(WorkspaceInspectionProjectionFailure.INSPECTION_OUTPUT_LIMIT)
    val message = terminalJson.encodeToString(received.copy(result = result))
    if (message.toByteArray(Charsets.UTF_8).size > maximumBytes)
        return Refinement.Rejected(WorkspaceInspectionProjectionFailure.INSPECTION_OUTPUT_LIMIT)
    return Refinement.Refined(reply.copy(message = message))
}

@Serializable
private data class WorkspaceInspectionReplyDocument(
    val id: kotlinx.serialization.json.JsonElement,
    val result: CodexDynamicToolResultDocument,
)

@Serializable
private data class WorkspaceExecutionInspectionDocument(
    val workspaceExecution: WorkspaceExecutionLaneSnapshot,
    val nativeAuthority: BrokerNativeAuthorityObservation = BrokerNativeAuthorityObservation.NOT_OBSERVED_BY_BROKER,
)

@Serializable
private enum class BrokerNativeAuthorityObservation {
    @SerialName("not_observed_by_broker") NOT_OBSERVED_BY_BROKER
}
