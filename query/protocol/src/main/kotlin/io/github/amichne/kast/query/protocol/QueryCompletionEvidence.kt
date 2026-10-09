package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection

/** Retained identity and proven preview remain distinct from the original enumeration qualification. */
internal fun completionQueryEvidence(
    retained: Refinement<QueryResultIssuance, QueryRunRejection>,
    question: QueryQuestionDocument,
    preview:
        (QueryResultIssuance.Issued) -> Refinement<BoundedProtocolList<QueryResultItemDocument>, QueryRunRejection>,
): Refinement<QueryCompletionEvidenceDocument, QueryRunRejection> =
    when (retained) {
        is Refinement.Rejected -> retained
        is Refinement.Refined ->
            when (val issued = retained.value) {
                is QueryResultIssuance.Issued ->
                    when (val selected = preview(issued)) {
                        is Refinement.Rejected -> selected
                        is Refinement.Refined ->
                            Refinement.Refined(
                                QueryCompletionEvidenceDocument.Retained(issued.reference, selected.value, question)
                            )
                    }
                QueryResultIssuance.Unavailable ->
                    Refinement.Refined(
                        QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.UNAVAILABLE)
                    )
                QueryResultIssuance.CapacityExceeded ->
                    Refinement.Refined(
                        QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.CAPACITY_EXCEEDED)
                    )
            }
    }
