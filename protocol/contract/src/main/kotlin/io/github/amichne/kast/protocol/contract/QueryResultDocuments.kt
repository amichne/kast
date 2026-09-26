package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

data class QueryExactLocationDocument(
    val file: ProtocolText,
    val range: SourceRangeDocument,
)

enum class QueryBindingRowDocumentFailure {
    DUPLICATE_NAME,
    DIFFERENT_SYMBOL_IDENTITIES,
    UNPROVEN_OCCURRENCE,
    NESTED_ROW_ID,
}

sealed interface QueryResultItemDocument {
    val rowId: QueryResultRowReference?

    data class ExactSymbol(
        val ref: QueryReferenceDocument.ExactSymbol,
        val kind: SymbolKindDocument,
        val name: ProtocolText?,
        val location: QueryExactLocationDocument?,
        val signature: CompilerSignatureDocument?,
        val connections: BoundedProtocolList<RelationFactDocument>,
        val symbolId: SymbolIdDocument,
        val source: QuerySourceWindowDocument? = null,
        override val rowId: QueryResultRowReference? = null,
    ) : QueryResultItemDocument

    data class Occurrence(
        val ref: QueryReferenceDocument.ExactSymbol,
        val relation: RelationFactDocument,
        override val rowId: QueryResultRowReference? = null,
    ) : QueryResultItemDocument

    data class TraversalRecord(
        val ref: QueryReferenceDocument.ExactSymbol,
        val record: TraversalRecordDocument,
        override val rowId: QueryResultRowReference? = null,
    ) : QueryResultItemDocument

    /** One inner-join pair; neither cell nor repeated pair occurrences are collapsed. */
    @ConsistentCopyVisibility
    data class BindingRow
    private constructor(
        val left: QueryBindingCellDocument,
        val right: QueryBindingCellDocument,
        override val rowId: QueryResultRowReference? = null,
    ) : QueryResultItemDocument {
        fun withRowId(value: QueryResultRowReference): BindingRow = copy(rowId = value)

        companion object {
            fun create(
                left: QueryBindingCellDocument,
                right: QueryBindingCellDocument,
                rowId: QueryResultRowReference? = null,
            ): Refinement<BindingRow, QueryBindingRowDocumentFailure> =
                when {
                    left.name == right.name -> Refinement.Rejected(QueryBindingRowDocumentFailure.DUPLICATE_NAME)
                    left.symbol.symbolId != right.symbol.symbolId ->
                        Refinement.Rejected(QueryBindingRowDocumentFailure.DIFFERENT_SYMBOL_IDENTITIES)
                    left.symbol.rowId != null || right.symbol.rowId != null ->
                        Refinement.Rejected(QueryBindingRowDocumentFailure.NESTED_ROW_ID)
                    !left.hasEstablishedOccurrence() || !right.hasEstablishedOccurrence() ->
                        Refinement.Rejected(QueryBindingRowDocumentFailure.UNPROVEN_OCCURRENCE)
                    else -> Refinement.Refined(BindingRow(left, right, rowId))
                }
        }
    }
}

private fun QueryBindingCellDocument.hasEstablishedOccurrence(): Boolean =
    when (this) {
        is QueryBindingCellDocument.Symbol -> true
        is QueryBindingCellDocument.Occurrence -> relation in symbol.connections.values
    }

/** A relation omission retains the exact subject and meaning that produced it. */
data class QueryRelationOmissionDocument(
    val subject: QueryReferenceDocument.ExactSymbol,
    val relation: RelationKindDocument,
    val evidence: RelationOmissionDocument,
) {
    companion object {
        val Empty: BoundedProtocolList<QueryRelationOmissionDocument> =
            (BoundedProtocolList.create(emptyList<QueryRelationOmissionDocument>()) as Refinement.Refined).value
    }
}

sealed interface QueryItemFailureDocument {
    data class Refinement(
        val ref: QueryReferenceDocument.DeclarationCandidate,
        val reason: QueryExactFailureDocument,
    ) : QueryItemFailureDocument

    data class ExactReference(
        val ref: QueryReferenceDocument.ExactSymbol,
        val reason: QueryExactFailureDocument,
    ) : QueryItemFailureDocument

    data class Predicate(
        val ref: QueryReferenceDocument.ExactSymbol,
        val reason: QueryPredicateFailureDocument,
    ) : QueryItemFailureDocument

    data class Source(
        val ref: QueryReferenceDocument.ExactSymbol,
        val reason: QuerySourceFailureDocument,
    ) : QueryItemFailureDocument

    data class Relation(
        val ref: QueryReferenceDocument.ExactSymbol,
        val relation: RelationKindDocument,
        val reason: QueryRelationFailureDocument,
    ) : QueryItemFailureDocument

    data class Walk(
        val ref: QueryReferenceDocument.ExactSymbol,
        val relation: RelationKindDocument,
        val reason: QueryWalkFailureDocument,
    ) : QueryItemFailureDocument
}

sealed interface QueryWalkFailureDocument {
    data class OneHop(val reason: QueryRelationFailureDocument) : QueryWalkFailureDocument

    data object RequiredEvidenceUnavailable : QueryWalkFailureDocument

    data object RequiredEvidenceStale : QueryWalkFailureDocument

    data object ReaderContractViolation : QueryWalkFailureDocument

    data object TraversalContractViolation : QueryWalkFailureDocument
}
