package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit

@ConsistentCopyVisibility
data class RunnerControlledRelationBudget private constructor(val value: RelationBudget) {
    companion object {
        fun create(returnedBytes: RelationByteLimit, pageResults: ResultLimit): RunnerControlledRelationBudget =
            RunnerControlledRelationBudget(RelationBudget(ResourceBudget.RunnerControlled, returnedBytes, pageResults))
    }
}
