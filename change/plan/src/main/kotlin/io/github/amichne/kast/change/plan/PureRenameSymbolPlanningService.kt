package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.AdmittedRenameSymbolPlanInput
import io.github.amichne.kast.change.contract.RenameSymbolChangePlan
import io.github.amichne.kast.change.contract.RenameSymbolPlanOperations
import io.github.amichne.kast.change.contract.RenameSymbolPlanRequest
import io.github.amichne.kast.change.contract.RenameSymbolPlanResult
import io.github.amichne.kast.kernel.Refinement

/** Pure RenameSymbol planner; it owns no physical capability or mutable state. */
class PureRenameSymbolPlanningService : RenameSymbolPlanOperations {
    /**
     * Proof transition: `RenameSymbolPlanRequest -> RenameSymbolPlanResult`.
     *
     * Refines complete target-bound evidence and issues one deterministic exact-occurrence plan.
     * Expected failure is closed by `RenameSymbolPlanningFailure`. Raw compiler and source values
     * remain outside this pure service.
     */
    override fun plan(request: RenameSymbolPlanRequest): RenameSymbolPlanResult = when (
        val admitted = AdmittedRenameSymbolPlanInput.admit(request)
    ) {
        is Refinement.Refined ->
            RenameSymbolPlanResult.Planned(RenameSymbolChangePlan.issue(admitted.value))
        is Refinement.Rejected -> RenameSymbolPlanResult.Rejected(admitted.failure)
    }
}
