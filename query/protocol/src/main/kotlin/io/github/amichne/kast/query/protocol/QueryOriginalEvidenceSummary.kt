package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryEvidenceFailureKind
import io.github.amichne.kast.protocol.contract.QueryEvidenceWalkFailureKind
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryOriginalEvidenceSummaryDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument
import io.github.amichne.kast.protocol.contract.QuerySourceFailureDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QuerySourceFailure
import io.github.amichne.kast.traversal.contract.TraversalRejection

/** Pure metadata over original facts, before presentation selection or issued-reference effects. */
internal fun QueryResult.originalEvidenceSummary(): QueryOriginalEvidenceSummaryDocument {
    val sourceFailures = failures.filterIsInstance<QueryItemFailure.Source>().map { it.reason }
    val walks = failures.filterIsInstance<QueryItemFailure.Walk>().map { it.reason }
    return QueryOriginalEvidenceSummaryDocument(
        summaryOffset(failures.size),
        summaryOffset(omissions.size),
        summaryOffset(evidenceCount()),
        finite(failures.map { it.summaryKind() }),
        finite(omissions.map { RelationLimitationDocument.valueOf(it.evidence.reason.name) }),
        finite(
            failures.filterIsInstance<QueryItemFailure.Refinement>().map {
                QueryExactFailureDocument.valueOf(it.reason.name)
            }
        ),
        finite(
            failures.filterIsInstance<QueryItemFailure.ExactReference>().map {
                QueryExactFailureDocument.valueOf(it.reason.name)
            }
        ),
        finite(failures.predicateReasons()),
        finite(sourceFailures.sourceReasons()),
        finite(
            sourceFailures.filterIsInstance<QuerySourceFailure.EnumerationIncomplete>().flatMap {
                it.qualification.limitations.map { limitation -> SourceReadLimitationDocument.valueOf(limitation.name) }
            }
        ),
        finite(
            failures.filterIsInstance<QueryItemFailure.Relation>().map {
                QueryRelationFailureDocument.valueOf(it.reason.name)
            }
        ),
        finite(
            walks.filterIsInstance<TraversalRejection.OneHopRejected>().map {
                QueryRelationFailureDocument.valueOf(it.reason.name)
            }
        ),
        finite(walks.map { it.summaryKind() }),
    )
}

private fun List<QueryItemFailure>.predicateReasons(): List<QueryPredicateFailureDocument> =
    filterIsInstance<QueryItemFailure.Visibility>().map { QueryPredicateFailureDocument.valueOf(it.reason.name) } +
        filterIsInstance<QueryItemFailure.PredicateUnproven>().map { QueryPredicateFailureDocument.PREDICATE_UNPROVEN }

private fun List<QuerySourceFailure>.sourceReasons(): List<QuerySourceFailureDocument> =
    filterIsInstance<QuerySourceFailure.Rejected>().map { QuerySourceFailureDocument.valueOf(it.reason.name) } +
        filterIsInstance<QuerySourceFailure.Withheld>().map { QuerySourceFailureDocument.valueOf(it.reason.name) }

private fun QueryItemFailure.summaryKind(): QueryEvidenceFailureKind =
    when (this) {
        is QueryItemFailure.Refinement -> QueryEvidenceFailureKind.REFINEMENT
        is QueryItemFailure.ExactReference -> QueryEvidenceFailureKind.EXACT_REFERENCE
        is QueryItemFailure.Visibility,
        is QueryItemFailure.PredicateUnproven -> QueryEvidenceFailureKind.PREDICATE
        is QueryItemFailure.Source ->
            when (reason) {
                is QuerySourceFailure.EnumerationIncomplete -> QueryEvidenceFailureKind.SOURCE_ENUMERATION_INCOMPLETE
                is QuerySourceFailure.Rejected,
                is QuerySourceFailure.Withheld -> QueryEvidenceFailureKind.SOURCE
            }
        is QueryItemFailure.Relation -> QueryEvidenceFailureKind.RELATION
        is QueryItemFailure.Walk -> QueryEvidenceFailureKind.WALK
    }

private fun TraversalRejection.summaryKind(): QueryEvidenceWalkFailureKind =
    when (this) {
        is TraversalRejection.OneHopRejected -> QueryEvidenceWalkFailureKind.ONE_HOP
        TraversalRejection.RequiredEvidenceUnavailable -> QueryEvidenceWalkFailureKind.REQUIRED_EVIDENCE_UNAVAILABLE
        TraversalRejection.RequiredEvidenceStale -> QueryEvidenceWalkFailureKind.REQUIRED_EVIDENCE_STALE
        TraversalRejection.ReaderContractViolation -> QueryEvidenceWalkFailureKind.READER_CONTRACT_VIOLATION
        TraversalRejection.TraversalContractViolation -> QueryEvidenceWalkFailureKind.TRAVERSAL_CONTRACT_VIOLATION
    }

private fun summaryOffset(value: Int): ProtocolOffset = ProtocolOffset.parse(value).required()

private fun <T : Enum<T>> finite(values: List<T>): BoundedProtocolList<T> =
    BoundedProtocolList.create(values.distinct().sortedBy { it.ordinal }).required()
