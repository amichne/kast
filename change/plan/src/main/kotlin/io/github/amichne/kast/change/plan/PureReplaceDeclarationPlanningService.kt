package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.AdmittedReplaceDeclarationPlanInput
import io.github.amichne.kast.change.contract.ReplaceDeclarationChangePlan
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanOperations
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanRequest
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanResult
import io.github.amichne.kast.kernel.Refinement

/** Pure ReplaceDeclaration planner; it owns no physical capability or mutable state. */
class PureReplaceDeclarationPlanningService : ReplaceDeclarationPlanOperations {
    /**
     * Proof transition: `ReplaceDeclarationPlanRequest -> ReplaceDeclarationPlanResult`.
     *
     * Refines a changed exact declaration and complete target-bound evidence before issuing one
     * deterministic plan. Expected failure is closed by `ReplaceDeclarationPlanningFailure`. Raw
     * source and compiler values remain outside this pure service.
     */
    override fun plan(
        request: ReplaceDeclarationPlanRequest,
    ): ReplaceDeclarationPlanResult = when (
        val admitted = AdmittedReplaceDeclarationPlanInput.admit(request)
    ) {
        is Refinement.Refined -> ReplaceDeclarationPlanResult.Planned(
            ReplaceDeclarationChangePlan.issue(admitted.value),
        )
        is Refinement.Rejected -> ReplaceDeclarationPlanResult.Rejected(admitted.failure)
    }
}
