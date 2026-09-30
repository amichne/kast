package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceOccurrenceDocument
import io.github.amichne.kast.query.contract.QueryResult

/** Collection proofs for every result projection are admitted together, so no established metadata can disappear. */
internal data class QueryProjectedEvidence(
    val items: List<QueryResultItemDocument>,
    val failures: BoundedProtocolList<QueryItemFailureDocument>,
    val omissions: BoundedProtocolList<QueryRelationOmissionDocument>,
    val walks: BoundedProtocolList<QueryWalkObservationDocument>,
    val references: BoundedProtocolList<RelationReferenceOccurrenceDocument>,
    val discoveries: BoundedProtocolList<QueryDiscoveryObservationDocument>,
) {
    companion object {
        fun from(
            result: QueryResult,
            output: QueryOutputDocument,
            authority: QueryReferenceAuthority,
        ): Refinement<QueryProjectedEvidence, QueryExecutionRejectionDocument> {
            val items =
                when (val projected = QueryItemProjector(authority).projectItems(output, result.rows)) {
                    is QueryProjection.Projected -> projected.values
                    QueryProjection.Rejected ->
                        return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
                }
            val boundedFailures =
                result.failures.mapProjected { it.projectIssue(authority) }.boundedProjectedOrNull()
                    ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
            val boundedOmissions =
                result.omissions.mapProjected { it.projectIssue(authority) }.boundedProjectedOrNull()
                    ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
            val boundedWalkObservations =
                result.walkObservations.mapProjected { it.projectWalkObservation(authority) }.boundedProjectedOrNull()
                    ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
            val boundedReferenceObservations =
                result.referenceObservations.mapProjected { it.protocolDocument(authority) }.boundedProjectedOrNull()
                    ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
            val boundedDiscoveryObservations =
                result.discoveryObservations.mapProjected { it.projectDiscoveryObservation() }.boundedProjectedOrNull()
                    ?: return Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
            return Refinement.Refined(
                QueryProjectedEvidence(
                    items,
                    boundedFailures,
                    boundedOmissions,
                    boundedWalkObservations,
                    boundedReferenceObservations,
                    boundedDiscoveryObservations,
                )
            )
        }
    }
}
