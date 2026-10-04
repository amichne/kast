package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryImpactExecutionStop
import io.github.amichne.kast.query.contract.QueryImpactSource
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowSuspensionCause
import io.github.amichne.kast.relation.contract.ValueFlowTerminal

internal sealed interface QueryImpactReadAcquisition {
    data object NotStarted : QueryImpactReadAcquisition

    data class Suspended(val cause: ValueFlowSuspensionCause) : QueryImpactReadAcquisition

    data class Cutoff(val stop: QueryImpactExecutionStop.CheckpointCapacity) : QueryImpactReadAcquisition

    data class Ready(val read: ValueFlowRead) : QueryImpactReadAcquisition
}

internal fun ValueFlowRead.examinedImpactWork(): RelationWorkCount =
    when (this) {
        is ValueFlowRead.Observed -> step.examinedWorkUnits
        is ValueFlowRead.Suspended -> step.examinedWorkUnits
        is ValueFlowRead.Rejected -> examinedWorkUnits
        is ValueFlowRead.ContractRejected -> examinedWorkUnits
    }

internal fun impactCapacityStop(
    route: QueryImpactRoute,
    required: Long,
    available: Long,
): QueryImpactExecutionStop.CheckpointCapacity =
    QueryImpactExecutionStop.CheckpointCapacity.admit(
            route.site,
            RelationByteCount.parse(required).proven(),
            RelationByteLimit.parse(available).proven(),
        )
        .proven()

private fun <V, F> Refinement<V, F>.proven(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("A proven capacity invariant was rejected: $failure")
    }

internal fun validateImpactRead(
    read: ValueFlowRead,
    request: ValueFlowRequest,
    source: QueryImpactSource,
): ValueFlowRead {
    val work = read.examinedImpactWork()
    if (work.value > request.budget.resources.workUnitLimit.value)
        return ValueFlowRead.ContractRejected(ValueFlowStepFailure.WORK_LIMIT_EXCEEDED, work)
    val page =
        when (read) {
            is ValueFlowRead.Observed -> read.step
            is ValueFlowRead.Suspended -> read.step
            is ValueFlowRead.Rejected,
            is ValueFlowRead.ContractRejected -> return read
        }
    if (page.receipts.size != 1 || page.domain.budget != request.budget)
        return ValueFlowRead.ContractRejected(ValueFlowStepFailure.DOMAIN_MISMATCH, work)
    val step =
        when (val admitted = source.admitObservation(page)) {
            is Refinement.Rejected -> return ValueFlowRead.ContractRejected(admitted.failure, page.examinedWorkUnits)
            is Refinement.Refined -> admitted.value
        }
    val remainderBytes = (read as? ValueFlowRead.Suspended)?.remainder?.retainedBytes ?: 0L
    val receipt = step.receipts.single()
    val failure =
        when {
            step.source != request.source -> ValueFlowStepFailure.SOURCE_MISMATCH
            step.domain.boundary != request.boundary -> ValueFlowStepFailure.DOMAIN_MISMATCH
            step.examinedWorkUnits.value > request.budget.resources.workUnitLimit.value ->
                ValueFlowStepFailure.WORK_LIMIT_EXCEEDED
            step.transfers.size > request.budget.resources.resultLimit.value ->
                ValueFlowStepFailure.RESULT_LIMIT_EXCEEDED
            step.retainedBytes > request.budget.returnedBytes.value - remainderBytes ->
                ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED
            receipt.returnedBytes.value != step.retainedBytes + remainderBytes ||
                receipt.returnedResults.value != step.transfers.size ||
                receipt.examinedWorkUnits != step.examinedWorkUnits -> ValueFlowStepFailure.INVALID_PROGRESS
            read is ValueFlowRead.Observed && step.terminal == ValueFlowTerminal.ResourceSuspended ->
                ValueFlowStepFailure.INVALID_PROGRESS
            read is ValueFlowRead.Suspended && !validSuspension(read, request) -> ValueFlowStepFailure.INVALID_PROGRESS
            else -> return read
        }
    return ValueFlowRead.ContractRejected(failure, step.examinedWorkUnits)
}

private fun validSuspension(read: ValueFlowRead.Suspended, request: ValueFlowRequest): Boolean {
    val next = read.remainder
    val previous = request.remainder
    return read.step.terminal == ValueFlowTerminal.ResourceSuspended &&
        next.source == request.source &&
        next.source.enclosing.lease == request.source.enclosing.lease &&
        next.boundary == request.boundary &&
        next.consumed.containsAll(previous?.consumed.orEmpty()) &&
        next.consumed.size > (previous?.consumed?.size ?: 0) &&
        next.emitted == previous?.emitted.orEmpty() + read.step.transfers.map { it.target.identity } &&
        read.step.transfers.none { it.target.identity in previous?.emitted.orEmpty() }
}
