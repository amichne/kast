package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalStrategy

/**
 * Proof transition: `(TraversalPlan, TraversalFrontierEntry, TraversalAccounting) -> TraversalReadAdmission`.
 *
 * Establishes either attenuated authority bounded by both the configured one-hop ceiling and the exact remaining
 * aggregate capacity, or one closed aggregate limitation before effects occur. [TraversalReadAdmission.Rejected] closes
 * impossible internal refinement failure. Raw counters remain inside the pure engine.
 */
internal fun admitTraversalRead(
    plan: TraversalPlan,
    next: TraversalFrontierEntry,
    accounting: TraversalAccounting,
): TraversalReadAdmission {
    val remainingRecords = plan.budget.records.value - accounting.records.size
    val remainingBytes = plan.budget.returnedBytes.value - accounting.encodedBytes
    val remainingWork = plan.budget.workUnits.value - accounting.examinedWorkUnits
    val remainingTime = plan.budget.elapsedTime.value - accounting.elapsedMillis
    return when {
        next.depth.value >= plan.budget.depth.value ->
            TraversalReadAdmission.Limited(TraversalLimitation.DEPTH_LIMIT_REACHED)
        accounting.expandedFrontier >= plan.budget.frontier.value ->
            TraversalReadAdmission.Limited(TraversalLimitation.FRONTIER_LIMIT_REACHED)
        remainingRecords <= 0 -> TraversalReadAdmission.Limited(TraversalLimitation.RECORD_LIMIT_REACHED)
        remainingBytes <= 0L -> TraversalReadAdmission.Limited(TraversalLimitation.BYTE_LIMIT_REACHED)
        remainingWork <= 0L -> TraversalReadAdmission.Limited(TraversalLimitation.WORK_LIMIT_REACHED)
        remainingTime <= 0L -> TraversalReadAdmission.Limited(TraversalLimitation.TIME_LIMIT_REACHED)
        else ->
            attenuatedBudget(
                plan.budget.oneHop,
                when (val strategy = plan.strategy) {
                    TraversalStrategy.BreadthFirst -> remainingRecords
                    is TraversalStrategy.BoundedFanOut -> minOf(remainingRecords, strategy.maximumEdgesPerNode.value)
                },
                remainingBytes,
                remainingWork,
                remainingTime,
            )
    }
}

/**
 * Proof transition: `(RelationBudget, positive remaining aggregate capacity) -> TraversalReadAdmission`.
 *
 * Establishes a one-hop budget that cannot exceed either its configured ceiling or the traversal capacity still
 * available. [TraversalReadAdmission.Rejected] is the closed internal refinement failure. Raw remaining counters may be
 * extracted only here.
 */
private fun attenuatedBudget(
    ceiling: RelationBudget,
    remainingRecords: Int,
    remainingBytes: Long,
    remainingWork: Long,
    remainingTime: Long,
): TraversalReadAdmission {
    val records =
        ResultLimit.parse(minOf(ceiling.resources.resultLimit.value, remainingRecords)).refinedOrNull()
            ?: return TraversalReadAdmission.Rejected
    val bytes =
        RelationByteLimit.parse(minOf(ceiling.returnedBytes.value, remainingBytes)).refinedOrNull()
            ?: return TraversalReadAdmission.Rejected
    val work =
        WorkUnitLimit.parse(minOf(ceiling.resources.workUnitLimit.value, remainingWork)).refinedOrNull()
            ?: return TraversalReadAdmission.Rejected
    val time =
        ElapsedTimeLimitMillis.parse(minOf(ceiling.resources.elapsedTimeLimit.value, remainingTime)).refinedOrNull()
            ?: return TraversalReadAdmission.Rejected
    return TraversalReadAdmission.Admitted(RelationBudget(ResourceBudget(records, work, time), bytes))
}

/**
 * Proof transition: `(OneHopRelationRequest, RelationBatch) -> ReaderBatchAdmission`.
 *
 * Establishes exact node, lease, scope, meaning, budget, and continuation retention. [ReaderBatchAdmission.Rejected] is
 * the closed expected failure. Raw provider objects remain outside the traversal core.
 */
internal fun admitTraversalBatch(
    request: OneHopRelationRequest,
    batch: RelationBatch,
): ReaderBatchAdmission {
    val relationRequest = batch.request
    val responsePosition = relationRequest.position
    val positionMatches =
        when (val position = request.position) {
            OneHopRelationPosition.Start -> responsePosition is RelationReadPosition.Start
            is OneHopRelationPosition.Resume ->
                responsePosition is RelationReadPosition.Resume &&
                    responsePosition.continuation.fingerprint == position.continuation.fingerprint
        }
    val matches =
        relationRequest.subject.fingerprint == request.node.fingerprint &&
            relationRequest.subject.lease == request.node.endpoint.lease &&
            relationRequest.subject.scope == request.scope &&
            relationRequest.meaning == request.meaning &&
            relationRequest.budget == request.budget &&
            positionMatches
    return if (matches) ReaderBatchAdmission.Accepted else ReaderBatchAdmission.Rejected
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
