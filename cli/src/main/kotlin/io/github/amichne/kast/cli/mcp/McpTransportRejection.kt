@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.protocol.contract.QueryDeferredDetailDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadQuery
import io.github.amichne.kast.protocol.wire.presentation.QueryTraceHeadline
import kotlinx.serialization.EncodeDefault

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Transport failures are separate from the unchanged canonical semantic result contracts. */
@Serializable
internal sealed interface McpTransportRejection {
    @Serializable
    @SerialName("OUTPUT_LIMIT")
    data class OutputLimit(
        val limitBytes: Int,
        val semantic: io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocument,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val headline: QueryTraceHeadline? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @SerialName("deferred_entry") val deferredEntry: QueryDeferredDetailDocument? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val nextQuery: QueryCompletionPublicReadQuery? = null,
        @Required val status: McpRejectedStatus = McpRejectedStatus.REJECTED,
    ) : McpTransportRejection

    @Serializable
    @SerialName("MCP_REJECTED")
    data class CallFailure(
        val error: McpCallError,
        @Required val status: McpRejectedStatus = McpRejectedStatus.REJECTED,
    ) : McpTransportRejection

    @Serializable
    @SerialName("BOUNDARY_REJECTED")
    data class BoundaryFailure(
        /** Opaque legacy CLI boundary document, retained verbatim rather than reinterpreting its cause. */
        val document: JsonObject,
        @Required val status: McpRejectedStatus = McpRejectedStatus.REJECTED,
    ) : McpTransportRejection
}

@Serializable
internal enum class McpRejectedStatus {
    @SerialName("rejected") REJECTED
}
