package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.QuerySourceWindowDocument
import io.github.amichne.kast.protocol.contract.SourceLineRangeDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class QuerySourceWindowWireDocument(val text: String, val lines: SourceLineRangeWireDocument)

@Serializable
internal data class QueryRefinementLocationWireDocument(val file: String, val offset: Int)

@Serializable
internal sealed interface QueryItemFailureWireDocument {
    @Serializable
    @SerialName("refinement")
    data class Refinement(
        val location: QueryRefinementLocationWireDocument,
        val reason: QueryExactFailureWireDocument,
    ) : QueryItemFailureWireDocument

    @Serializable
    @SerialName("exact-reference")
    data class ExactReference(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val reason: QueryExactFailureWireDocument,
    ) : QueryItemFailureWireDocument

    @Serializable
    @SerialName("predicate")
    data class Predicate(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val reason: QueryPredicateFailureWireDocument,
    ) : QueryItemFailureWireDocument

    @Serializable
    @SerialName("source")
    data class Source(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val reason: QuerySourceFailureWireDocument,
    ) : QueryItemFailureWireDocument

    @Serializable
    @SerialName("relation")
    data class Relation(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val relation: RelationKindWireDocument,
        val reason: QueryRelationFailureWireDocument,
    ) : QueryItemFailureWireDocument

    @Serializable
    @SerialName("walk")
    data class Walk(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val relation: RelationKindWireDocument,
        val reason: QueryWalkFailureWireDocument,
    ) : QueryItemFailureWireDocument
}

@Serializable
internal enum class QuerySourceFailureWireDocument {
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SOURCE_STATE_MISMATCH,
    CANDIDATE_STALE,
    SOURCE_SELECTOR_STALE,
    SOURCE_SNAPSHOT_MISMATCH,
    SOURCE_UNAVAILABLE,
    CONTINUATION_UNAVAILABLE,
    CONTINUATION_REQUEST_MISMATCH,
    DOCUMENT_DIRTY,
    PSI_DOCUMENT_UNCOMMITTED,
    OUTSIDE_SOURCE_SCOPE,
    ANCHOR_NOT_FOUND,
    AMBIGUOUS_ANCHOR,
    REGION_NOT_APPLICABLE,
    REGION_ABSENT,
    COMPILER_ANALYSIS_UNAVAILABLE,
    INTERNAL_CONTEXT_LEASE_MISMATCH,
    INTERNAL_SNAPSHOT_CONTEXT_MISMATCH,
    INTERNAL_SCOPE_MISMATCH,
    INTERNAL_SNAPSHOT_MISMATCH,
    INTERNAL_VISIBILITY_MISMATCH,
    CONTRACT_VIOLATION,
    BYTE_LIMIT_REACHED,
    PROVIDER_UNAVAILABLE,
}

internal fun QuerySourceWindowWireDocument?.toContract(): WireDocumentConversion<QuerySourceWindowDocument?> =
    if (this == null) WireDocumentConversion.Converted(null)
    else
        combineConverted(
            ProtocolSourceText.parse(text).toWireDocumentConversion(),
            SourceLineRangeDocument.parse(lines.startInclusive, lines.endInclusive).toWireDocumentConversion(),
            ::QuerySourceWindowDocument,
        )
