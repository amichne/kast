package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.protocol.wire.presentation.RelationFactCliDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Typed query request and occurrence response for an exact relation probe. */
@Serializable internal data class McpQueryOccurrenceDocument(val items: List<McpQueryOccurrence>)

@Serializable
internal data class McpQueryOccurrence(val type: McpQueryOccurrenceKind, val relation: RelationFactCliDocument)

@Serializable
internal enum class McpQueryOccurrenceKind {
    @SerialName("occurrence") OCCURRENCE
}

@Serializable internal data class McpQueryRelationRequest(val request: McpQueryRunAction)

@Serializable
internal data class McpQueryRunAction(
    val source: McpQuerySymbolRefs,
    val steps: List<McpQueryExpandRelation>,
    val output: McpQueryOccurrenceOutput = McpQueryOccurrenceOutput(),
    val action: McpQueryAction = McpQueryAction.RUN,
)

@Serializable
internal enum class McpQueryAction {
    @SerialName("run") RUN
}

@Serializable
internal data class McpQuerySymbolRefs(
    @SerialName("symbol_refs") val symbolRefs: List<String>,
    val type: McpQuerySourceKind = McpQuerySourceKind.SYMBOL_REFS,
)

@Serializable
internal enum class McpQuerySourceKind {
    @SerialName("symbol_refs") SYMBOL_REFS
}

@Serializable
internal data class McpQueryExpandRelation(
    val relation: McpValidationRelationKind,
    val type: McpQueryStepKind = McpQueryStepKind.EXPAND_RELATION,
)

@Serializable
internal enum class McpQueryStepKind {
    @SerialName("expand_relation") EXPAND_RELATION
}

@Serializable
internal data class McpQueryOccurrenceOutput(val type: McpQueryOutputKind = McpQueryOutputKind.OCCURRENCES)

@Serializable
internal enum class McpQueryOutputKind {
    @SerialName("occurrences") OCCURRENCES
}
