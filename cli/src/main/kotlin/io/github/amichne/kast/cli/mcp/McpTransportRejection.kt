package io.github.amichne.kast.cli.mcp

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Transport failures are separate from the unchanged canonical semantic result contracts. */
@Serializable
internal sealed interface McpTransportRejection {
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
