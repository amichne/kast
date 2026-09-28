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
    val type: McpQueryAction = McpQueryAction.RUN,
)

@Serializable
internal enum class McpQueryAction {
    @SerialName("RUN") RUN
}

@Serializable
internal data class McpQuerySymbolRefs(
    val symbolRefs: List<String>,
    val type: McpQuerySourceKind = McpQuerySourceKind.SYMBOL_REFS,
)

@Serializable
internal enum class McpQuerySourceKind {
    @SerialName("SYMBOL_REFS") SYMBOL_REFS
}

@Serializable
internal data class McpQueryExpandRelation(
    val relation: McpQueryRelationKind,
    val type: McpQueryStepKind = McpQueryStepKind.EXPAND_RELATION,
)

@Serializable
internal enum class McpQueryRelationKind {
    REFERENCES,
    CALLERS,
    CALLEES,
    IMPLEMENTATIONS,
    INHERITORS,
    OVERRIDES,
    TYPE_USES,
}

internal fun McpValidationRelationKind.queryRelation(): McpQueryRelationKind =
    when (this) {
        McpValidationRelationKind.REFERENCES -> McpQueryRelationKind.REFERENCES
        McpValidationRelationKind.CALLERS -> McpQueryRelationKind.CALLERS
        McpValidationRelationKind.CALLEES -> McpQueryRelationKind.CALLEES
        McpValidationRelationKind.IMPLEMENTATIONS -> McpQueryRelationKind.IMPLEMENTATIONS
        McpValidationRelationKind.INHERITORS -> McpQueryRelationKind.INHERITORS
        McpValidationRelationKind.OVERRIDES -> McpQueryRelationKind.OVERRIDES
        McpValidationRelationKind.TYPE_USES -> McpQueryRelationKind.TYPE_USES
    }

@Serializable
internal enum class McpQueryStepKind {
    @SerialName("EXPAND_RELATION") EXPAND_RELATION
}

@Serializable
internal data class McpQueryOccurrenceOutput(val type: McpQueryOutputKind = McpQueryOutputKind.OCCURRENCES)

@Serializable
internal enum class McpQueryOutputKind {
    @SerialName("OCCURRENCES") OCCURRENCES
}
