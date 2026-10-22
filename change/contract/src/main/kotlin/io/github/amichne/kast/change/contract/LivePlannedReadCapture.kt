package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundedRelationBudget
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalResult

internal fun captureLiveRelation(
    result: RelationReadResult.Complete
): Refinement<LivePlannedRelationRead, ChangePlanningFailure> =
    when (val finite = BoundedRelationBudget.admit(result.batch.request.budget)) {
        is Refinement.Rejected -> Refinement.Rejected(ChangePlanningFailure.RELATION_EVIDENCE_INCOMPLETE)
        is Refinement.Refined ->
            Refinement.Refined(
                LivePlannedRelationRead(result.batch.request.meaning, finite.value, result.batch.request.boundary)
            )
    }

internal fun captureLiveTraversal(
    result: TraversalResult.Complete
): Refinement<LivePlannedTraversal, ChangePlanningFailure> =
    when (val budget = result.page.plan.budget) {
        is TraversalBudget.RunnerControlled -> Refinement.Rejected(ChangePlanningFailure.TRAVERSAL_EVIDENCE_INCOMPLETE)
        is TraversalBudget.Bounded ->
            when (val hop = BoundedRelationBudget.admit(budget.oneHop)) {
                is Refinement.Rejected -> Refinement.Rejected(ChangePlanningFailure.TRAVERSAL_EVIDENCE_INCOMPLETE)
                is Refinement.Refined ->
                    Refinement.Refined(
                        LivePlannedTraversal(
                            meaning = result.page.plan.meaning,
                            budget = budget,
                            boundedOneHop = hop.value,
                            expansion = result.page.plan.expansion,
                        )
                    )
            }
    }
