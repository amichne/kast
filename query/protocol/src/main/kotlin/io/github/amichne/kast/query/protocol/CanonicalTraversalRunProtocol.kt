package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TraversalRunPositionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalResult as DomainTraversalResult
import io.github.amichne.kast.traversal.contract.TraversalStrategy
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

class CanonicalTraversalRunProtocol(
    private val operations: TraversalOperations,
    private val authority: QueryReferenceAuthority,
) {
    suspend fun execute(
        request: TraversalRunRequest,
        current: SemanticReadAuthority,
        maximum: TraversalBudget,
    ): OperationOutcome<
        TraversalRunResult,
        TraversalRunQualification,
        TraversalRunRejection,
    > {
        val selector =
            when (val lookup = authority.exact(request.exactSelector, current)) {
                is ExactSelectorLookup.Found -> lookup.selector
                is ExactSelectorLookup.Rejected -> return OperationOutcome.Rejected(lookup.reason.traversalProtocol())
            }
        if (request.maximumDepth.value > maximum.depth.value)
            return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val records =
            ResultLimit.parse(minOf(request.maximumResults.value, maximum.records.value)).refinedOrNull()
                ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val depth =
            TraversalDepthLimit.parse(request.maximumDepth.value).refinedOrNull()
                ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val oneHopRecords =
            ResultLimit.parse(minOf(records.value, maximum.oneHop.resources.resultLimit.value)).refinedOrNull()
                ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val budget =
            maximum.copy(
                records = records,
                depth = depth,
                oneHop = maximum.oneHop.copy(resources = maximum.oneHop.resources.copy(resultLimit = oneHopRecords)),
            )
        val strategy =
            admitStrategy(request.strategy) ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val plan =
            when (val admitted = admitPlan(request, selector, budget, strategy, current)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(admitted.failure)
            }
        return when (val result = operations.run(plan)) {
            is DomainTraversalResult.Rejected -> OperationOutcome.Rejected(result.reason.protocol())
            is DomainTraversalResult.Complete ->
                projectTraversal(
                    result.page,
                    plan,
                    TraversalProjection.Complete,
                )
            is DomainTraversalResult.Qualified ->
                projectTraversal(
                    result.page,
                    plan,
                    TraversalProjection.Qualified(result.qualification),
                )
        }
    }

    private fun admitPlan(
        request: TraversalRunRequest,
        selector: io.github.amichne.kast.symbol.contract.SymbolSelector,
        budget: TraversalBudget,
        strategy: TraversalStrategy,
        current: SemanticReadAuthority,
    ): Refinement<TraversalPlan, TraversalRunRejection> {
        val meaning = request.relation.graphMeaning()
        return when (val position = request.position) {
            TraversalRunPositionDocument.Start ->
                when (val admitted = TraversalPlan.start(selector, meaning, budget, strategy)) {
                    is Refinement.Refined -> admitted
                    is Refinement.Rejected -> return Refinement.Rejected(TraversalRunRejection.PLAN_REJECTED)
                }
            is TraversalRunPositionDocument.Resume -> {
                val continuation =
                    when (
                        val decoded =
                            CanonicalTraversalContinuationCodec.decode(
                                position.continuation,
                                budget,
                                authority,
                                current,
                            )
                    ) {
                        is CanonicalTraversalContinuationDecoding.Decoded -> decoded.continuation
                        CanonicalTraversalContinuationDecoding.SubjectMismatch ->
                            return Refinement.Rejected(TraversalRunRejection.CONTINUATION_SUBJECT_MISMATCH)
                        CanonicalTraversalContinuationDecoding.AuthorityMismatch ->
                            return Refinement.Rejected(TraversalRunRejection.CONTINUATION_GENERATION_MISMATCH)
                        CanonicalTraversalContinuationDecoding.Malformed ->
                            return Refinement.Rejected(TraversalRunRejection.CONTINUATION_MALFORMED)
                    }
                when (val admitted = TraversalPlan.resume(selector, meaning, budget, continuation, strategy)) {
                    is Refinement.Refined -> admitted
                    is Refinement.Rejected -> return Refinement.Rejected(admitted.failure.protocol())
                }
            }
        }
    }

    private fun admitStrategy(requestedStrategy: TraversalStrategyDocument): TraversalStrategy? {
        return when (val requested = requestedStrategy) {
            TraversalStrategyDocument.BreadthFirst -> TraversalStrategy.BreadthFirst
            is TraversalStrategyDocument.BoundedFanOut ->
                TraversalStrategy.BoundedFanOut(
                    ResultLimit.parse(requested.maximumEdgesPerNode.value).refinedOrNull() ?: return null
                )
        }
    }

    private fun recordDocuments(page: TraversalPage): BoundedProtocolList<TraversalRecordDocument>? {
        val documents = mutableListOf<TraversalRecordDocument>()
        page.records.forEach { record ->
            val relation = record.fact.protocolDocument(authority) ?: return null
            val depth =
                when (val admitted = TraversalDepthDocument.parse(record.depth.value)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return null
                }
            documents += TraversalRecordDocument(depth, relation)
        }
        return when (val admitted = BoundedProtocolList.create(documents)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return null
        }
    }

    private fun projectTraversal(
        page: TraversalPage,
        plan: TraversalPlan,
        projection: TraversalProjection,
    ): OperationOutcome<TraversalRunResult, TraversalRunQualification, TraversalRunRejection> {
        val bounded = recordDocuments(page) ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
        val snapshotRoot =
            when (val admitted = ProtocolText.parse(plan.start.lease.workspaceRoot.value)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            }
        val partials =
            when (val admitted = page.partialExpansions.protocolDocuments(authority)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
            }
        val envelope =
            EvidenceEnvelope(
                CanonicalOperation.TRAVERSAL_RUN.id,
                plan.start.lease.evidenceBasis(),
                TraversalRunResult(
                    snapshotRoot,
                    bounded,
                    TraversalProgressDocument(
                        page.progress.checkpointSequence,
                        page.progress.totalReads,
                        page.progress.totalEdges,
                        page.progress.maximumDepthReached,
                    ),
                    when (val selected = plan.strategy) {
                        TraversalStrategy.BreadthFirst -> TraversalStrategyDocument.BreadthFirst
                        is TraversalStrategy.BoundedFanOut ->
                            TraversalStrategyDocument.BoundedFanOut(
                                ProtocolCount.parse(selected.maximumEdgesPerNode.value).refinedOrNull()
                                    ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
                            )
                    },
                    partialExpansions = partials,
                ),
            )
        return when (projection) {
            TraversalProjection.Complete -> OperationOutcome.Complete(envelope)
            is TraversalProjection.Qualified -> {
                val qualification =
                    projection.qualification.protocolQualification(authority, page.records.size)
                        ?: return OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED)
                OperationOutcome.Qualified(envelope, qualification)
            }
        }
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
