package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ReviewedValueSemantics
import io.github.amichne.kast.relation.contract.ValueTransferKind

/** Relationship strength and evidence authority remain independent facts. */
enum class QueryImpactRelationship {
    EXACT,
    DERIVED,
}

enum class QueryImpactCorrespondenceFailure {
    REPRESENTATION_MODEL_ONLY,
    BOUNDARY_MODEL_ONLY,
}

sealed interface QueryImpactClassification {
    data class Established(val relationship: QueryImpactRelationship) : QueryImpactClassification

    data class NotEstablished(val step: QueryImpactStep, val cause: QueryImpactCorrespondenceFailure) :
        QueryImpactClassification
}

internal fun List<QueryImpactStep>.classifyImpact(): QueryImpactClassification =
    fold<QueryImpactStep, QueryImpactClassification>(
        QueryImpactClassification.Established(QueryImpactRelationship.EXACT)
    ) { previous, step ->
        when (previous) {
            is QueryImpactClassification.NotEstablished -> previous
            is QueryImpactClassification.Established ->
                when (val current = step.classification()) {
                    is QueryImpactClassification.NotEstablished -> current
                    is QueryImpactClassification.Established ->
                        QueryImpactClassification.Established(
                            if (
                                previous.relationship == QueryImpactRelationship.DERIVED ||
                                    current.relationship == QueryImpactRelationship.DERIVED
                            )
                                QueryImpactRelationship.DERIVED
                            else QueryImpactRelationship.EXACT
                        )
                }
        }
    }

private fun QueryImpactStep.classification(): QueryImpactClassification =
    when (this) {
        is QueryImpactStep.Compiler -> QueryImpactClassification.Established(transfer.kind.relationship())
        is QueryImpactStep.ReviewedLibrary ->
            when (transfer.semantics) {
                ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT,
                ReviewedValueSemantics.CONDITIONAL_RECEIVER_TO_CALL_RESULT,
                ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT ->
                    QueryImpactClassification.Established(QueryImpactRelationship.EXACT)
            }
        is QueryImpactStep.ModeledRepresentation ->
            QueryImpactClassification.NotEstablished(this, QueryImpactCorrespondenceFailure.REPRESENTATION_MODEL_ONLY)
        is QueryImpactStep.ModeledBoundary ->
            QueryImpactClassification.NotEstablished(this, QueryImpactCorrespondenceFailure.BOUNDARY_MODEL_ONLY)
    }

private fun ValueTransferKind.relationship(): QueryImpactRelationship =
    when (this) {
        ValueTransferKind.STRING_CONTENT -> QueryImpactRelationship.DERIVED
        ValueTransferKind.LOCAL_BINDING,
        ValueTransferKind.LOCAL_READ,
        ValueTransferKind.ARGUMENT,
        ValueTransferKind.RETURN,
        ValueTransferKind.PROPERTY_ASSIGNMENT,
        ValueTransferKind.BRANCH_ALTERNATIVE,
        ValueTransferKind.WRAPPER_RETURN,
        ValueTransferKind.RECEIVER,
        ValueTransferKind.SOURCE_CALL_ENTRY,
        ValueTransferKind.SOURCE_CALL_RETURN,
        ValueTransferKind.FIELD_READ,
        ValueTransferKind.CONSTRUCTOR_FIELD,
        ValueTransferKind.CAST_RESULT -> QueryImpactRelationship.EXACT
    }
