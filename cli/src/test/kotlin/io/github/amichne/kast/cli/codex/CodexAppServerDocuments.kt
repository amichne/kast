package io.github.amichne.kast.cli.codex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class RpcRequestDocument(
    val id: Long,
    val method: String,
    val params: JsonElement,
)

@Serializable
internal data class RpcNotificationDocument(
    val method: String,
)

@Serializable
internal data class RpcResponseDocument(
    val id: JsonElement,
    val result: DynamicToolCallResponseDocument,
)

@Serializable
internal data class RpcIncomingDocument(
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonElement? = null,
)

@Serializable
internal data class InitializeParamsDocument(
    val clientInfo: ClientInfoDocument,
    val capabilities: InitializeCapabilitiesDocument,
)

@Serializable
internal data class ClientInfoDocument(
    val name: String,
    val title: String? = null,
    val version: String,
)

@Serializable
internal data class InitializeCapabilitiesDocument(
    val experimentalApi: Boolean,
    val requestAttestation: Boolean,
)

@Serializable
internal data class ThreadStartParamsDocument(
    val cwd: String,
    val approvalPolicy: String,
    val sandbox: String,
    val ephemeral: Boolean,
    val experimentalRawEvents: Boolean,
    val dynamicTools: List<DynamicToolNamespaceDocument>,
)

@Serializable
internal data class DynamicToolNamespaceDocument(
    val type: String,
    val name: String,
    val description: String,
    val tools: List<DynamicToolFunctionDocument>,
)

@Serializable
internal data class DynamicToolFunctionDocument(
    val type: String,
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
    val deferLoading: Boolean,
)

@Serializable
internal data class ThreadStartResultDocument(
    val thread: ThreadIdentityDocument,
)

@Serializable
internal data class ThreadIdentityDocument(
    val id: String,
)

@Serializable
internal data class TurnStartParamsDocument(
    val threadId: String,
    val input: List<TextUserInputDocument>,
)

@Serializable
internal data class TextUserInputDocument(
    val type: String,
    val text: String,
    @SerialName("text_elements") val textElements: List<JsonElement>,
)

@Serializable
internal data class DynamicToolCallParamsDocument(
    val threadId: String,
    val turnId: String,
    val callId: String,
    val namespace: String? = null,
    val tool: String,
    val arguments: JsonElement,
)

@Serializable
internal data class DynamicToolCallResponseDocument(
    val contentItems: List<DynamicToolOutputTextDocument>,
    val success: Boolean,
)

@Serializable
internal data class DynamicToolOutputTextDocument(
    val type: String,
    val text: String,
)

@Serializable
internal data class ItemNotificationParamsDocument(
    val item: JsonElement,
    val threadId: String,
    val turnId: String,
)

@Serializable
internal data class ObservedThreadItemDocument(
    val type: String,
    val command: String? = null,
    val text: String? = null,
)

@Serializable
internal data class ObservedRawResponseItemDocument(
    val type: String,
    val name: String? = null,
    val input: String? = null,
)

@Serializable
internal data class RawResponseCompletedParamsDocument(
    val threadId: String,
    val turnId: String,
    val responseId: String,
    val usage: TokenUsageBreakdownDocument? = null,
)

@Serializable
internal data class ThreadTokenUsageParamsDocument(
    val threadId: String,
    val turnId: String,
    val tokenUsage: ThreadTokenUsageDocument,
)

@Serializable
internal data class ThreadTokenUsageDocument(
    val total: TokenUsageBreakdownDocument,
    val last: TokenUsageBreakdownDocument,
    val modelContextWindow: Long? = null,
)

@Serializable
internal data class TokenUsageBreakdownDocument(
    val totalTokens: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val cacheWriteInputTokens: Long,
    val outputTokens: Long,
    val reasoningOutputTokens: Long,
)

@Serializable
internal data class TurnCompletedParamsDocument(
    val threadId: String,
    val turn: TurnCompletionDocument,
)

@Serializable
internal data class TurnCompletionDocument(
    val id: String,
    val status: String,
)

@Serializable
internal data class CodexDynamicToolsEvidenceDocument(
    val completed: Boolean,
    val threadId: String,
    val turnId: String,
    val appServerCommand: List<String>,
    val modelTokensBeforeFirstUsefulKastResult: Long,
    val modelResponseCompletions: Int,
    val modelToolTurns: Int,
    val dynamicToolCalls: Int,
    val toolSearchCalls: Int,
    val malformedInvocations: Int,
    val correctiveInvocations: Int,
    val deferredDiscoveryObserved: Boolean,
    val fullDeferredSchemasPresentBeforeDiscovery: Boolean,
    val selectorRoundTripUnchanged: Boolean,
    val commandExecutionObserved: Boolean,
    val kastProcessExecutionObserved: Boolean,
    val correctRelationResult: Boolean,
    val relationTargetNames: List<String>,
    val relationQualificationNames: List<String>,
    val relationRejectionNames: List<String>,
    val equivalentCliInvocations: Int,
    val finalAnswer: String,
)
