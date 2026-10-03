package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceOccurrenceDocument
import io.github.amichne.kast.query.contract.QueryResult

/** Collection proofs for every result projection are admitted together, so no established metadata can disappear. */
internal data class QueryProjectedEvidence(
    val items: List<QueryResultItemDocument>,
    val impactAccounting: io.github.amichne.kast.protocol.contract.ImpactAccountingDocument,
    val failures: BoundedProtocolList<QueryItemFailureDocument>,
    val omissions: BoundedProtocolList<QueryRelationOmissionDocument>,
    val walks: BoundedProtocolList<QueryWalkObservationDocument>,
    val references: BoundedProtocolList<RelationReferenceOccurrenceDocument>,
    val discoveries: BoundedProtocolList<QueryDiscoveryObservationDocument>,
    val relations: BoundedProtocolList<io.github.amichne.kast.protocol.contract.QueryRelationObservationDocument>,
) {
    companion object {
        fun from(
            result: QueryResult,
            output: QueryOutputDocument,
            authority: QueryReferenceAuthority,
        ): Refinement<QueryProjectedEvidence, QueryRunRejection> {
            val items =
                when (val admitted = projectResultItems(result, output, authority)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val impactAccounting =
                when (val projected = result.rows.impactAccountingDocument()) {
                    is Refinement.Refined -> projected.value
                    is Refinement.Rejected -> return Refinement.Rejected(projected.failure.presentationRejection())
                }
            val boundedFailures =
                result.failures.mapProjected { it.projectIssue(authority) }.boundedProjectedOrNull()
                    ?: return internalProjectionRejection()
            val boundedOmissions =
                result.omissions.mapProjected { it.projectIssue(authority) }.boundedProjectedOrNull()
                    ?: return internalProjectionRejection()
            val boundedWalkObservations =
                result.walkObservations.mapProjected { it.projectWalkObservation(authority) }.boundedProjectedOrNull()
                    ?: return internalProjectionRejection()
            val boundedReferenceObservations =
                result.referenceObservations.mapProjected { it.protocolDocument(authority) }.boundedProjectedOrNull()
                    ?: return internalProjectionRejection()
            val boundedDiscoveryObservations =
                result.discoveryObservations.mapProjected { it.projectDiscoveryObservation() }.boundedProjectedOrNull()
                    ?: return internalProjectionRejection()
            val boundedRelationObservations =
                result.relationObservations
                    .mapProjected { it.projectRelationObservation(authority) }
                    .boundedProjectedOrNull() ?: return internalProjectionRejection()
            return Refinement.Refined(
                QueryProjectedEvidence(
                    items,
                    impactAccounting,
                    boundedFailures,
                    boundedOmissions,
                    boundedWalkObservations,
                    boundedReferenceObservations,
                    boundedDiscoveryObservations,
                    boundedRelationObservations,
                )
            )
        }
    }
}

private fun projectResultItems(
    result: QueryResult,
    output: QueryOutputDocument,
    authority: QueryReferenceAuthority,
): Refinement<List<QueryResultItemDocument>, QueryRunRejection> =
    when (val projected = QueryItemProjector(authority).projectItems(output, result.rows)) {
        is QueryProjection.Projected -> Refinement.Refined(projected.values)
        is QueryProjection.ImpactRejected -> Refinement.Rejected(projected.cause.presentationRejection())
        QueryProjection.Rejected ->
            Refinement.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
            )
    }

private fun internalProjectionRejection() =
    Refinement.Rejected(
        QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
    )
