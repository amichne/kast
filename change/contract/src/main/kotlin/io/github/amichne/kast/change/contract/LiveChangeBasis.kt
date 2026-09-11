package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/** Historical evidence only. Neither variant grants current read or mutation authority. */
sealed interface ChangePlanningBasis {
    data class Published(val lease: SemanticReadLease, val state: WorkspaceStateIdentity) : ChangePlanningBasis

    data class Live(val observation: LiveChangeBasis) : ChangePlanningBasis
}

enum class LiveChangeBasisFailure {
    WRONG_ROOT,
    VERSION_UNSUPPORTED,
}

enum class LiveChangeBasisComparison {
    UNCHANGED,
    WRONG_ROOT,
    WRONG_OWNER,
    VERSION_UNSUPPORTED,
    EPOCH_MOVED,
    MODEL_MOVED,
}

/** Complete detached model and exact original-owner epoch; comparison cannot revive its authority. */
class LiveChangeBasis
private constructor(
    val reference: LiveSemanticReadReference,
    val model: WorkspaceSearchScopeModel,
) {
    fun compare(
        current: LiveSemanticReadReference,
        currentModel: WorkspaceSearchScopeModel,
    ): LiveChangeBasisComparison =
        when {
            current.version != LiveSemanticReadReference.VERSION -> LiveChangeBasisComparison.VERSION_UNSUPPORTED
            current.workspaceRoot != reference.workspaceRoot || currentModel.workspaceRoot != reference.workspaceRoot ->
                LiveChangeBasisComparison.WRONG_ROOT
            current.host != reference.host -> LiveChangeBasisComparison.WRONG_OWNER
            current.epoch != reference.epoch || current.contentView != reference.contentView ->
                LiveChangeBasisComparison.EPOCH_MOVED
            currentModel.sourceRoots != model.sourceRoots -> LiveChangeBasisComparison.MODEL_MOVED
            else -> LiveChangeBasisComparison.UNCHANGED
        }

    companion object {
        /** Refines coherent detached observations, without admitting any live operation. */
        fun observe(
            reference: LiveSemanticReadReference,
            model: WorkspaceSearchScopeModel,
        ): Refinement<LiveChangeBasis, LiveChangeBasisFailure> =
            when {
                reference.version != LiveSemanticReadReference.VERSION ->
                    Refinement.Rejected(LiveChangeBasisFailure.VERSION_UNSUPPORTED)
                reference.workspaceRoot != model.workspaceRoot -> Refinement.Rejected(LiveChangeBasisFailure.WRONG_ROOT)
                else -> Refinement.Refined(LiveChangeBasis(reference, model))
            }
    }
}
