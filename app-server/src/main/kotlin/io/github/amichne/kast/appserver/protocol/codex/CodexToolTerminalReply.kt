package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.ToolPresentation
import io.github.amichne.kast.appserver.schema.canonicalJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
