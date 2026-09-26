package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

data class QueryExactLocationDocument(
    val file: ProtocolText,
    val range: SourceRangeDocument,
)

sealed interface QueryResultItemDocument {
    val ref: QueryReferenceDocument

    data class ExactSymbol(
        override val ref: QueryReferenceDocument.ExactSymbol,
        val kind: SymbolKindDocument,
        val name: ProtocolText?,
        val location: QueryExactLocationDocument?,
        val signature: CompilerSignatureDocument?,
        val connections: BoundedProtocolList<RelationFactDocument>,
        val symbolId: SymbolIdDocument,
        val source: QuerySourceWindowDocument? = null,
        val rowId: QueryResultRowReference? = null,
    ) : QueryResultItemDocument

    data class Occurrence(
        override val ref: QueryReferenceDocument.ExactSymbol,
        val relation: RelationFactDocument,
        val rowId: QueryResultRowReference? = null,
    ) : QueryResultItemDocument

    data class TraversalRecord(
        override val ref: QueryReferenceDocument.ExactSymbol,
        val record: TraversalRecordDocument,
        val rowId: QueryResultRowReference? = null,
    ) : QueryResultItemDocument
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
