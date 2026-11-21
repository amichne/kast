package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.mcp.McpChangePhase
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_CONTRACT_VERSION
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The public CLI's closed reply document and its finite failures. */
internal fun presentToolRpcExit(name: String, exit: CliExit): ToolRpcReply {
    val document =
        try {
            toolRpcJson.parseToJsonElement(exit.document.value) as? JsonObject
                ?: return ToolRpcReply.Rejected(ToolRpcFailure.INVALID_RESULT)
        } catch (_: SerializationException) {
            return ToolRpcReply.Rejected(ToolRpcFailure.INVALID_RESULT)
        }
    val admitted =
        when (exit) {
            // Legacy CLI boundary documents have their own closed serializers, outside semantic schemas.
            is CliExit.BoundaryRejected,
            is CliExit.PublicInputRejected -> true
            is CliExit.Complete,
            is CliExit.Qualified,
            is CliExit.OperationRejected ->
                McpStructuredResults.validatesSemantic(name, document) &&
                    McpStructuredResults.matchesExit(exit, document)
            is CliExit.Delegated -> false
        }
    if (!admitted) return ToolRpcReply.Rejected(ToolRpcFailure.INVALID_RESULT)
    return when (exit) {
        is CliExit.Complete -> ToolRpcReply.Complete(document)
        is CliExit.Qualified -> ToolRpcReply.Qualified(document)
        is CliExit.OperationRejected,
        is CliExit.BoundaryRejected -> ToolRpcReply.RejectedDocument(document)
        is CliExit.PublicInputRejected -> ToolRpcReply.Rejected.publicInput(exit.failure)
        is CliExit.Delegated -> ToolRpcReply.Rejected(ToolRpcFailure.INVALID_RESULT)
    }
}

internal fun decodeToolRpcInput(bytes: ByteArray): Refinement<String, ToolRpcFailure> =
    try {
        Refinement.Refined(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (_: CharacterCodingException) {
        Refinement.Rejected(ToolRpcFailure.INVALID_ARGUMENTS)
    }

@Serializable
internal sealed interface ToolRpcReply {
    @Serializable @SerialName("catalog") data class Catalog(val catalog: ToolRpcCatalog) : ToolRpcReply

    @Serializable @SerialName("complete") data class Complete(val document: JsonObject) : ToolRpcReply

    @Serializable @SerialName("qualified") data class Qualified(val document: JsonObject) : ToolRpcReply

    @Serializable @SerialName("rejected_document") data class RejectedDocument(val document: JsonObject) : ToolRpcReply

    @Serializable
    @SerialName("rejected")
    data class Rejected
    private constructor(
        val failure: ToolRpcFailure,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val detail: QuerySessionRejectionDetail? = null,
    ) : ToolRpcReply {
        constructor(failure: ToolRpcFailure) : this(failure, null)

        companion object {
            fun publicInput(failure: PublicToolInputFailure): Rejected =
                Rejected(ToolRpcFailure.INVALID_ARGUMENTS, publicToolInputRejectionDetail(failure))

            fun querySession(detail: QuerySessionRejectionDetail): Rejected =
                Rejected(
                    when (detail) {
                        is QuerySessionRejectionDetail.RequestConstraint,
                        is QuerySessionRejectionDetail.OutputAllowance,
                        is QuerySessionRejectionDetail.DetailSelector,
                        is QuerySessionRejectionDetail.SourceAnchor -> ToolRpcFailure.INVALID_ARGUMENTS
                        is QuerySessionRejectionDetail.NewRun,
                        is QuerySessionRejectionDetail.SourceRead,
                        is QuerySessionRejectionDetail.ResultNotIssued,
                        is QuerySessionRejectionDetail.ContinuationNotIssued ->
                            ToolRpcFailure.QUERY_SESSION_TRANSITION_REJECTED
                    },
                    detail,
                )
        }
    }
}

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal data class ToolRpcCatalog(
    val tools: List<ToolRpcTool>,
    @Required val schemaVersion: Int = PUBLIC_TOOL_CONTRACT_VERSION,
    @Required
    val callTimeoutMillis: Long =
        OperationExecutionBudget.WORKSPACE_READINESS.value +
            McpChangePhase.entries.size * OperationExecutionBudget.SEMANTIC_READ.operation.value,
    @Required val maxResponseBytes: Int = BrokerOperationalLimits.maximumToolRpcResponseBytes,
    /** Optional transport capability; absence in an older catalog selects one-shot calls. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    val querySession: Boolean = true,
)

@Serializable
internal data class ToolRpcTool(
    val name: String,
    val description: String,
    val effect: ToolRpcToolEffect,
    /** Generated canonical request schema, retained unchanged until the ToolRpc projection. */
    val inputSchema: JsonObject,
)

@Serializable
internal enum class ToolRpcToolEffect {
    READ,
    WRITE,
}

@Serializable
internal enum class ToolRpcFailure {
    INSTALLATION_STOPPED,
    OBSERVATION_UNAVAILABLE,
    CATALOG_UNAVAILABLE,
    INVALID_COMMAND,
    REQUEST_TOO_LARGE,
    UNKNOWN_TOOL,
    INVALID_ARGUMENTS,
    OUT_OF_SCOPE,
    INVOCATION_FAILED,
    INVALID_RESULT,
    QUERY_SESSION_TRANSITION_REJECTED,
}

private val toolRpcJson = Json { encodeDefaults = true }
