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
    val remainingRecords = plan.budget.records.value - accounting.retainedResultCount
    val remainingBytes =
        when (val budget = plan.budget) {
            is io.github.amichne.kast.traversal.contract.TraversalBudget.RunnerControlled -> budget.returnedBytes.value
            is io.github.amichne.kast.traversal.contract.TraversalBudget.Bounded ->
                budget.returnedBytes.value - accounting.encodedBytes
        }
    return when {
        !plan.budget.extent.permitsExpansion(next.depth) ->
            TraversalReadAdmission.Limited(TraversalLimitation.DEPTH_LIMIT_REACHED)
        !plan.budget.hasFrontierCapacity(accounting.expandedFrontier) ->
            TraversalReadAdmission.Limited(TraversalLimitation.FRONTIER_LIMIT_REACHED)
        remainingRecords <= 0 -> TraversalReadAdmission.Limited(TraversalLimitation.RECORD_LIMIT_REACHED)
        remainingBytes <= 0L -> TraversalReadAdmission.Limited(TraversalLimitation.BYTE_LIMIT_REACHED)
        !plan.budget.resources.hasWorkCapacity(accounting.examinedWorkUnits) ->
            TraversalReadAdmission.Limited(TraversalLimitation.WORK_LIMIT_REACHED)
        !plan.budget.resources.hasTimeRemaining(accounting.elapsedMillis) ->
            TraversalReadAdmission.Limited(TraversalLimitation.TIME_LIMIT_REACHED)
        else ->
            attenuatedBudget(
                plan.budget.oneHop,
                when (val strategy = plan.strategy) {
                    TraversalStrategy.BreadthFirst -> remainingRecords
                    is TraversalStrategy.BoundedFanOut -> minOf(remainingRecords, strategy.maximumEdgesPerNode.value)
                },
                remainingBytes,
                plan.budget.resources,
                accounting,
            )
    }
}

private fun attenuatedBudget(
    ceiling: RelationBudget,
    remainingRecords: Int,
    remainingBytes: Long,
    aggregate: ResourceBudget,
    accounting: TraversalAccounting,
): TraversalReadAdmission =
    when (val records = ResultLimit.parse(minOf(ceiling.outputResults.value, remainingRecords))) {
        is Refinement.Rejected -> TraversalReadAdmission.Rejected
        is Refinement.Refined ->
            when (val bytes = RelationByteLimit.parse(minOf(ceiling.returnedBytes.value, remainingBytes))) {
                is Refinement.Rejected -> TraversalReadAdmission.Rejected
                is Refinement.Refined ->
                    attenuatedResources(
                        resources = ceiling.resources,
                        records = records.value,
                        bytes = bytes.value,
                        aggregate = aggregate,
                        accounting = accounting,
                    )
            }
    }

private fun attenuatedResources(
    resources: ResourceBudget,
    records: ResultLimit,
    bytes: RelationByteLimit,
    aggregate: ResourceBudget,
    accounting: TraversalAccounting,
): TraversalReadAdmission =
    when (resources) {
        ResourceBudget.RunnerControlled -> TraversalReadAdmission.Admitted(RelationBudget(resources, bytes, records))
        is ResourceBudget.Bounded ->
            when (aggregate) {
                ResourceBudget.RunnerControlled ->
                    boundedBudget(
                        ceiling = resources,
                        records = records,
                        bytes = bytes,
                        remainingWork = resources.workUnitLimit.value,
                        remainingTime = resources.elapsedTimeLimit.value,
                    )
                is ResourceBudget.Bounded ->
                    boundedBudget(
                        ceiling = resources,
                        records = records,
                        bytes = bytes,
                        remainingWork = aggregate.workUnitLimit.value - accounting.examinedWorkUnits,
                        remainingTime = aggregate.elapsedTimeLimit.value - accounting.elapsedMillis,
                    )
            }
    }

private fun boundedBudget(
    ceiling: ResourceBudget.Bounded,
    records: ResultLimit,
    bytes: RelationByteLimit,
    remainingWork: Long,
    remainingTime: Long,
): TraversalReadAdmission =
    when (val work = WorkUnitLimit.parse(minOf(ceiling.workUnitLimit.value, remainingWork))) {
        is Refinement.Rejected -> TraversalReadAdmission.Rejected
        is Refinement.Refined ->
            when (val time = ElapsedTimeLimitMillis.parse(minOf(ceiling.elapsedTimeLimit.value, remainingTime))) {
                is Refinement.Rejected -> TraversalReadAdmission.Rejected
                is Refinement.Refined ->
                    TraversalReadAdmission.Admitted(
                        RelationBudget(ResourceBudget(records, work.value, time.value), bytes)
                    )
            }
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
            relationRequest.searchScope == request.scope &&
            relationRequest.boundary == request.expansion &&
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
