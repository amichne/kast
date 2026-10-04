package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalReferenceObservationDocument
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
    val projectedCoverage = coverage.protocolDocument() ?: return null
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
        strategy = strategy.protocolDocument() ?: return null,
        partialExpansions = partials,
        coverage = projectedCoverage,
        requestedDomain = question.requestedDomain.requestedDomainDocument(),
        effectiveDomain = relationDomainDocument(question.effectiveScope, question.effectiveConstraints) ?: return null,
        domainFingerprint =
            io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint.parse(
                    question.domainFingerprint.value
                )
                .refinedWalkOrNull() ?: return null,
        inheritedOmissions = inheritedOmissions.protocolDocuments(authority) ?: return null,
        referenceOccurrences = referenceOccurrences.protocolReferences(authority) ?: return null,
        scopeExclusions =
            scopeExclusions.mapProjected { it.projectScopeExclusion(authority) }.boundedProjectedOrNull()
                ?: return null,
    )
}

private fun QueryWalkCoverage.protocolDocument(): QueryWalkCoverageDocument? =
    when (this) {
        QueryWalkCoverage.Complete -> QueryWalkCoverageDocument.Complete
        is QueryWalkCoverage.Resumable ->
            QueryWalkCoverageDocument.resumable(
                    limitations.map { it.protocolDocument() },
                    relationLimitations.map { it.protocolDocument() },
                )
                .refinedWalkOrNull()
        is QueryWalkCoverage.TerminalIncomplete ->
            QueryWalkCoverageDocument.terminalIncomplete(
                    limitations.map { it.protocolDocument() },
                    relationLimitations.map { it.protocolDocument() },
                )
                .refinedWalkOrNull()
    }

private fun TraversalStrategy.protocolDocument(): TraversalStrategyDocument? {
    return when (this) {
        TraversalStrategy.BreadthFirst -> TraversalStrategyDocument.BreadthFirst
        is TraversalStrategy.BoundedFanOut ->
            TraversalStrategyDocument.BoundedFanOut(
                ProtocolCount.parse(maximumEdgesPerNode.value).refinedWalkOrNull() ?: return null
            )
    }
}

private fun List<io.github.amichne.kast.traversal.contract.TraversalReferenceObservation>.protocolReferences(
    authority: QueryReferenceAuthority
): BoundedProtocolList<TraversalReferenceObservationDocument>? {
    val projected = map { observed ->
        val expandedSubject =
            when (val issued = authority.issueEndpoint(observed.entry.node.endpoint)) {
                is RelationEndpointIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
                is RelationEndpointIssuance.Rejected -> return null
            }
        TraversalReferenceObservationDocument(
            expandedSubject,
            TraversalDepthDocument.parse(observed.entry.depth.value).refinedWalkOrNull() ?: return null,
            observed.reference.protocolDocument(authority) ?: return null,
        )
    }
    return BoundedProtocolList.create(projected).refinedWalkOrNull()
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
        RelationLimitation.CANDIDATE_LIMIT_REACHED -> RelationLimitationDocument.CANDIDATE_LIMIT_REACHED
        RelationLimitation.RETENTION_LIMIT_REACHED -> RelationLimitationDocument.RETENTION_LIMIT_REACHED
        RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE -> RelationLimitationDocument.PARTITION_INVENTORY_UNAVAILABLE
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
