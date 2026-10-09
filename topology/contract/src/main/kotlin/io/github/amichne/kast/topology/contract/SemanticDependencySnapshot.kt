package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.LiveSemanticEnvironmentContinuityFailure
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

/** Content identities over complete native inventories; URLs, SDK names and modification counters are insufficient. */
data class SemanticResolutionInputs(
    val sdk: WorkspaceSourceContentHash,
    val compilerConfiguration: WorkspaceSourceContentHash,
    val classpath: WorkspaceSourceContentHash,
)

sealed interface SemanticSnapshotAdmissionFailure {
    data object WorkspaceMismatch : SemanticSnapshotAdmissionFailure

    data object ResolutionInputDomainMismatch : SemanticSnapshotAdmissionFailure

    data class Authority(val cause: LiveSemanticReadFailure) : SemanticSnapshotAdmissionFailure
}

sealed interface SemanticSnapshotReuseFailure {
    data class Environment(val cause: LiveSemanticEnvironmentContinuityFailure) : SemanticSnapshotReuseFailure

    data class Authority(val cause: LiveSemanticReadFailure) : SemanticSnapshotReuseFailure

    data object ResolutionInputsChanged : SemanticSnapshotReuseFailure

    data object DependencyDomainChanged : SemanticSnapshotReuseFailure

    data class SourcesChanged(val change: SemanticInventoryComparison.Changed) : SemanticSnapshotReuseFailure
}

/** Source and resolution inventories captured under one original-owner authority. No callback completion is implied. */
class SemanticDependencySnapshot
private constructor(
    val authority: LiveSemanticReadAuthority,
    val inventory: SemanticDependencyInventory,
    val inputs: SemanticResolutionInputInventory,
) {
    val retainedBytes: Long =
        inventory.closure.graph.retainedBytes +
            4096L * inventory.closure.modules.size +
            4096L +
            inventory.files.sumOf {
                4096L + it.path.value.length.toLong() * 2L
            }

    fun reuseFrom(
        previous: SemanticDependencySnapshot
    ): Refinement<SemanticSnapshotReuseProof, SemanticSnapshotReuseFailure> =
        when (val guarded = authority.withCurrentOwner { admitReuse(previous) }) {
            is Refinement.Refined -> guarded.value
            is Refinement.Rejected -> Refinement.Rejected(SemanticSnapshotReuseFailure.Authority(guarded.failure))
        }

    private fun admitReuse(
        previous: SemanticDependencySnapshot
    ): Refinement<SemanticSnapshotReuseProof, SemanticSnapshotReuseFailure> {
        when (val environment = authority.environmentContinuityFrom(previous.authority)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected ->
                return Refinement.Rejected(SemanticSnapshotReuseFailure.Environment(environment.failure))
        }
        if (inputs.modules != previous.inputs.modules)
            return Refinement.Rejected(SemanticSnapshotReuseFailure.ResolutionInputsChanged)
        when (val comparison = previous.inventory.compare(inventory)) {
            SemanticInventoryComparison.Unchanged -> Unit
            SemanticInventoryComparison.DomainChanged ->
                return Refinement.Rejected(SemanticSnapshotReuseFailure.DependencyDomainChanged)
            is SemanticInventoryComparison.Changed ->
                return Refinement.Rejected(SemanticSnapshotReuseFailure.SourcesChanged(comparison))
        }
        return Refinement.Refined(SemanticSnapshotReuseProof(previous, this))
    }

    companion object {
        /**
         * Native caller must capture every inventory within the same admitted read, before this detached transition.
         */
        fun fromCompiler(
            authority: LiveSemanticReadAuthority,
            inventory: SemanticDependencyInventory,
            inputs: SemanticResolutionInputInventory,
        ): Refinement<SemanticDependencySnapshot, SemanticSnapshotAdmissionFailure> {
            if (inventory.closure.graph.model.workspaceRoot != authority.workspaceRoot)
                return Refinement.Rejected(SemanticSnapshotAdmissionFailure.WorkspaceMismatch)
            if (inventory.closure.graph !== inputs.closure.graph || !inventory.closure.sameDomain(inputs.closure))
                return Refinement.Rejected(SemanticSnapshotAdmissionFailure.ResolutionInputDomainMismatch)
            return when (
                val guarded = authority.withCurrentOwner { SemanticDependencySnapshot(authority, inventory, inputs) }
            ) {
                is Refinement.Refined -> guarded
                is Refinement.Rejected ->
                    Refinement.Rejected(SemanticSnapshotAdmissionFailure.Authority(guarded.failure))
            }
        }
    }
}

/** Permits re-admission of unchanged detached facts; never authorizes reuse of an old lease or continuation. */
class SemanticSnapshotReuseProof
internal constructor(
    val previous: SemanticDependencySnapshot,
    val current: SemanticDependencySnapshot,
)
