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

internal sealed interface QueryImpactReadAcquisition {
    data object NotStarted : QueryImpactReadAcquisition

    data class Cutoff(val stop: QueryImpactExecutionStop.CheckpointCapacity) : QueryImpactReadAcquisition

    data class Ready(val read: ValueFlowRead) : QueryImpactReadAcquisition
}

internal fun ValueFlowRead.examinedImpactWork(): RelationWorkCount =
    when (this) {
        is ValueFlowRead.Observed -> step.examinedWorkUnits
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
    if (read !is ValueFlowRead.Observed) return read
    val step =
        when (val admitted = source.admitObservation(read.step)) {
            is Refinement.Rejected ->
                return ValueFlowRead.ContractRejected(admitted.failure, read.step.examinedWorkUnits)
            is Refinement.Refined -> admitted.value
        }
    val failure =
        when {
            step.source != request.source -> ValueFlowStepFailure.SOURCE_MISMATCH
            step.domain.boundary != request.boundary -> ValueFlowStepFailure.DOMAIN_MISMATCH
            step.examinedWorkUnits.value > request.budget.resources.workUnitLimit.value ->
                ValueFlowStepFailure.WORK_LIMIT_EXCEEDED
            step.transfers.size > request.budget.resources.resultLimit.value ->
                ValueFlowStepFailure.RESULT_LIMIT_EXCEEDED
            step.retainedBytes > request.budget.returnedBytes.value -> ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED
            else -> return read
        }
    return ValueFlowRead.ContractRejected(failure, step.examinedWorkUnits)
}
