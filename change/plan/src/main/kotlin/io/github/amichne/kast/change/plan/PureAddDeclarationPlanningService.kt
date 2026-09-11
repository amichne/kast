package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddDeclarationPlanOperations
import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.contract.AdmittedAddDeclarationPlanInput
import io.github.amichne.kast.change.contract.AdmittedLiveAddDeclarationPlanInput
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanResult
import io.github.amichne.kast.kernel.Refinement

/** Pure AddDeclaration planner; it owns no physical capability or mutable state. */
class PureAddDeclarationPlanningService : AddDeclarationPlanOperations {
    /** Consumes live admission only to produce detached proof; no published lease or write capability is created. */
    fun plan(request: LiveAddDeclarationPlanRequest): LiveAddDeclarationPlanResult =
        when (val admitted = AdmittedLiveAddDeclarationPlanInput.admit(request)) {
            is Refinement.Refined ->
                LiveAddDeclarationPlanResult.Planned(LiveAddDeclarationChangePlan.issue(admitted.value))
            is Refinement.Rejected -> LiveAddDeclarationPlanResult.Rejected(admitted.failure)
        }

    /**
     * Proof transition: `AddDeclarationPlanRequest -> AddDeclarationPlanResult`.
     *
     * Refines complete target-bound semantic evidence and issues one deterministic detached plan. Expected failure is
     * closed by `AddDeclarationPlanningFailure` through [AddDeclarationPlanResult.Rejected]. Raw source and compiler
     * extraction are prohibited in this service; later apply boundaries must admit separate mutation authority.
     */
    override fun plan(request: AddDeclarationPlanRequest): AddDeclarationPlanResult =
        when (val admitted = AdmittedAddDeclarationPlanInput.admit(request)) {
            is Refinement.Refined -> AddDeclarationPlanResult.Planned(AddDeclarationChangePlan.issue(admitted.value))
            is Refinement.Rejected -> AddDeclarationPlanResult.Rejected(admitted.failure)
        }
}
