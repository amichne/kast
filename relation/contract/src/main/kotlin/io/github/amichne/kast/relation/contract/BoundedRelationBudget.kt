package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget

enum class BoundedRelationBudgetFailure {
    RUNNER_CONTROLLED
}

@ConsistentCopyVisibility
data class BoundedRelationBudget
private constructor(
    val value: RelationBudget,
    val resources: ResourceBudget.Bounded,
) {
    companion object {
        fun admit(budget: RelationBudget): Refinement<BoundedRelationBudget, BoundedRelationBudgetFailure> =
            when (val resources = budget.resources) {
                ResourceBudget.RunnerControlled -> Refinement.Rejected(BoundedRelationBudgetFailure.RUNNER_CONTROLLED)
                is ResourceBudget.Bounded -> Refinement.Refined(BoundedRelationBudget(budget, resources))
            }
    }
}
