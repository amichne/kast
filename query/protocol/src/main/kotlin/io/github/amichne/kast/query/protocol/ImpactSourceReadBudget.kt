package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit

internal suspend fun remainingImpactReadBudget(
    authority: QueryReferenceAuthority,
    budget: QueryBudget,
    examinedWork: Long,
    retainedBytes: Long,
    position: Int,
): Refinement<RelationBudget, QueryRunRejection> {
    val current =
        when (val observed = authority.remainingReadBudget(budget.resources)) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected ->
                return impactFailure(
                    when (observed.failure) {
                        ReadReacquisitionBudgetFailure.WORK_LIMIT_REACHED ->
                            QueryImpactSourceFailureCode.WORK_LIMIT_REACHED
                        ReadReacquisitionBudgetFailure.TIME_LIMIT_REACHED ->
                            QueryImpactSourceFailureCode.TIME_LIMIT_REACHED
                    },
                    position,
                )
        }
    val work =
        when (
            val admitted =
                WorkUnitLimit.parse(
                    minOf(current.workUnitLimit.value, budget.resources.workUnitLimit.value - examinedWork)
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return impactFailure(QueryImpactSourceFailureCode.WORK_LIMIT_REACHED, position)
        }
    val bytes =
        when (val admitted = RelationByteLimit.parse(budget.checkpointBytes.value - retainedBytes)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return impactFailure(QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED, position)
        }
    return Refinement.Refined(RelationBudget(current.copy(workUnitLimit = work), bytes))
}
