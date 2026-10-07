package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CompleteNamedRelationPartition
import io.github.amichne.kast.relation.contract.NamedRelationReadmission
import io.github.amichne.kast.relation.contract.NamedRelationReadmissionFailure
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.readmitNamedRelations

internal fun readmitNamedRelationPartition(
    previous: CompleteNamedRelationPartition,
    request: RelationRequest,
    projection: IntellijK2RelationProjection,
    scope: CompiledRelationScope,
    allowance: IntellijRelationAllowance,
): NamedRelationReadmission {
    if (previous.facts.size > request.budget.resources.resultLimit.value)
        return Refinement.Rejected(
            NamedRelationReadmissionFailure.Batch(
                io.github.amichne.kast.relation.contract.RelationBatchFailure.RESULT_LIMIT_EXCEEDED
            )
        )
    for (fact in previous.facts) when (allowance.admitCallbackWork(request.budget.resources)) {
        CallbackWorkAdmission.READY -> Unit
        CallbackWorkAdmission.WORK_LIMIT_REACHED ->
            return Refinement.Rejected(
                NamedRelationReadmissionFailure.Endpoint(
                    io.github.amichne.kast.relation.contract.CallbackSummaryReadmissionFailure.WorkLimitReached
                )
            )
        CallbackWorkAdmission.TIME_LIMIT_REACHED ->
            return Refinement.Rejected(
                NamedRelationReadmissionFailure.Endpoint(
                    io.github.amichne.kast.relation.contract.CallbackSummaryReadmissionFailure.TimeLimitReached
                )
            )
    }
    return when (
        val endpoints =
            readmitCallbackEndpoints(previous.endpoints, request, projection, scope) {
                allowance.admitCallbackWork(request.budget.resources)
            }
    ) {
        is Refinement.Rejected -> Refinement.Rejected(NamedRelationReadmissionFailure.Endpoint(endpoints.failure))
        is Refinement.Refined -> {
            val count = RelationWorkCount.parse(allowance.examined)
            check(count is Refinement.Refined)
            endpoints.value.readmitNamedRelations(previous, request, count.value)
        }
    }
}
