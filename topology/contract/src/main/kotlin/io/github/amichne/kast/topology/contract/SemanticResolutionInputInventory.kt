package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import java.util.Collections

sealed interface SemanticResolutionInputFailure {
    data class MissingModules(val modules: Set<WorkspaceModuleIdentity>) : SemanticResolutionInputFailure

    data class UnknownModules(val modules: Set<WorkspaceModuleIdentity>) : SemanticResolutionInputFailure

    data object ForeignGraph : SemanticResolutionInputFailure
}

/** Complete native resolution inputs for each module in one admitted dependency closure. */
class SemanticResolutionInputInventory
private constructor(
    val closure: SemanticDependencyClosure,
    inputs: Map<WorkspaceModuleIdentity, SemanticResolutionInputs>,
) {
    val modules: Map<WorkspaceModuleIdentity, SemanticResolutionInputs> = Collections.unmodifiableMap(inputs.toMap())

    /** A smaller closure retains every dependency's SDK, classpath and compiler configuration. */
    fun narrow(
        selected: SemanticDependencyClosure
    ): Refinement<SemanticResolutionInputInventory, SemanticResolutionInputFailure> {
        if (selected.graph !== closure.graph) return Refinement.Rejected(SemanticResolutionInputFailure.ForeignGraph)
        return fromCompiler(selected, modules.filterKeys { it in selected.modules })
    }

    companion object {
        fun fromCompiler(
            closure: SemanticDependencyClosure,
            inputs: Map<WorkspaceModuleIdentity, SemanticResolutionInputs>,
        ): Refinement<SemanticResolutionInputInventory, SemanticResolutionInputFailure> {
            val detached = inputs.toMap()
            val missing = closure.modules - detached.keys
            if (missing.isNotEmpty()) return Refinement.Rejected(SemanticResolutionInputFailure.MissingModules(missing))
            val unknown = detached.keys - closure.modules
            if (unknown.isNotEmpty()) return Refinement.Rejected(SemanticResolutionInputFailure.UnknownModules(unknown))
            return Refinement.Refined(SemanticResolutionInputInventory(closure, detached))
        }
    }
}
