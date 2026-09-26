package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.query.contract.QueryWalkCoverage
import io.github.amichne.kast.query.contract.QueryWalkObservation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalStrategy

/** Retains the traversal engine's page evidence inside the one query result projection. */
internal fun QueryWalkObservation.projectWalkObservation(
    authority: QueryReferenceAuthority
): QueryWalkObservationDocument? {
    val subject =
        when (val issued = authority.issueExact(subject)) {
            is ExactSelectorIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
            is ExactSelectorIssuance.Rejected -> return null
        }
    val depth = ProtocolCount.parse(budget.depth.value).refinedWalkOrNull() ?: return null
    val frontier = QueryExpandedFrontierDocument.parse(expandedFrontier.value).refinedWalkOrNull() ?: return null
    val partials = partialExpansions.protocolDocuments(authority) ?: return null
    val projectedCoverage =
        when (val selected = coverage) {
            QueryWalkCoverage.Complete -> QueryWalkCoverageDocument.Complete
            is QueryWalkCoverage.Resumable ->
                QueryWalkCoverageDocument.resumable(
                        selected.limitations.map { it.protocolDocument() },
                        selected.relationLimitations.map { it.protocolDocument() },
                    )
                    .refinedWalkOrNull() ?: return null
            is QueryWalkCoverage.TerminalIncomplete ->
                QueryWalkCoverageDocument.terminalIncomplete(
                        selected.limitations.map { it.protocolDocument() },
                        selected.relationLimitations.map { it.protocolDocument() },
                    )
                    .refinedWalkOrNull() ?: return null
        }
    return QueryWalkObservationDocument(
        subject = subject,
        relation = meaning.protocolDocument(),
        maximumDepth = depth,
        expandedFrontier = frontier,
        progress =
            TraversalProgressDocument(
                progress.checkpointSequence,
                progress.totalReads,
                progress.totalEdges,
                progress.maximumDepthReached,
            ),
        strategy =
            when (val selected = strategy) {
                TraversalStrategy.BreadthFirst -> TraversalStrategyDocument.BreadthFirst
                is TraversalStrategy.BoundedFanOut ->
                    TraversalStrategyDocument.BoundedFanOut(
                        ProtocolCount.parse(selected.maximumEdgesPerNode.value).refinedWalkOrNull() ?: return null
                    )
            },
        partialExpansions = partials,
        coverage = projectedCoverage,
    )
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedWalkOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

internal fun RelationLimitation.protocolDocument(): RelationLimitationDocument =
    when (this) {
        RelationLimitation.RESULT_LIMIT_REACHED -> RelationLimitationDocument.RESULT_LIMIT_REACHED
        RelationLimitation.BYTE_LIMIT_REACHED -> RelationLimitationDocument.BYTE_LIMIT_REACHED
        RelationLimitation.WORK_LIMIT_REACHED -> RelationLimitationDocument.WORK_LIMIT_REACHED
        RelationLimitation.TIME_LIMIT_REACHED -> RelationLimitationDocument.TIME_LIMIT_REACHED
        RelationLimitation.DUMB_MODE_TRANSITION -> RelationLimitationDocument.DUMB_MODE_TRANSITION
        RelationLimitation.UNRESOLVED_TARGET -> RelationLimitationDocument.UNRESOLVED_TARGET
        RelationLimitation.UNSUPPORTED_ITEM -> RelationLimitationDocument.UNSUPPORTED_ITEM
        RelationLimitation.PROVIDER_FAILURE -> RelationLimitationDocument.PROVIDER_FAILURE
        RelationLimitation.PROVIDER_INCOMPLETE -> RelationLimitationDocument.PROVIDER_INCOMPLETE
        RelationLimitation.PROVIDER_STALLED -> RelationLimitationDocument.PROVIDER_STALLED
    }

internal fun TraversalLimitation.protocolDocument(): TraversalLimitationDocument =
    when (this) {
        TraversalLimitation.RECORD_LIMIT_REACHED -> TraversalLimitationDocument.RECORD_LIMIT_REACHED
        TraversalLimitation.BYTE_LIMIT_REACHED -> TraversalLimitationDocument.BYTE_LIMIT_REACHED
        TraversalLimitation.WORK_LIMIT_REACHED -> TraversalLimitationDocument.WORK_LIMIT_REACHED
        TraversalLimitation.TIME_LIMIT_REACHED -> TraversalLimitationDocument.TIME_LIMIT_REACHED
        TraversalLimitation.DEPTH_LIMIT_REACHED -> TraversalLimitationDocument.DEPTH_LIMIT_REACHED
        TraversalLimitation.FRONTIER_LIMIT_REACHED -> TraversalLimitationDocument.FRONTIER_LIMIT_REACHED
        TraversalLimitation.ONE_HOP_INCOMPLETE -> TraversalLimitationDocument.ONE_HOP_INCOMPLETE
        TraversalLimitation.NO_PROGRESS -> TraversalLimitationDocument.NO_PROGRESS
    }
