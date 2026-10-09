package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteSemanticModuleSources
import io.github.amichne.kast.topology.contract.SemanticDependencyClosure
import io.github.amichne.kast.topology.contract.SemanticDependencyInventory
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.contract.SemanticModuleDependencies
import io.github.amichne.kast.topology.contract.SemanticResolutionInputInventory
import io.github.amichne.kast.topology.contract.SemanticResolutionInputs
import io.github.amichne.kast.topology.contract.SemanticSnapshotAdmissionFailure
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/**
 * One native attempt owns the exact graph carrier and complete per-module observations. No rebinding of graph proof.
 */
internal class SemanticDependencyReadInputs<Graph>(
    private val authority: LiveSemanticReadAuthority,
    private val model: WorkspaceSearchScopeModel,
    private val domain: (Graph) -> SemanticModuleDependencies,
) {
    private sealed interface State<out Graph> {
        data object Pending : State<Nothing>

        class Unavailable(val cause: SemanticDependencyCaptureFailure) : State<Nothing>

        class Active<Graph>(val graph: Graph) : State<Graph> {
            val modules = linkedMapOf<WorkspaceModuleIdentity, SemanticCapture<NativeModuleInputs>>()
        }

        data object Closed : State<Nothing>
    }

    private var state: State<Graph> = State.Pending
    private val modules = model.sourceRoots.mapTo(linkedSetOf()) { it.module }

    fun admitRead(): SemanticCapture<Unit> =
        when (state) {
            State.Closed -> captureRejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED)
            State.Pending,
            is State.Unavailable,
            is State.Active -> Refinement.Refined(Unit)
        }

    fun snapshot(
        roots: Set<WorkspaceModuleIdentity>,
        budget: DependencyCaptureBudget,
        captureGraph: () -> SemanticCapture<Graph>,
        captureModule:
            (Graph, SemanticDependencyClosure, WorkspaceModuleIdentity) -> SemanticCapture<NativeModuleInputs>,
    ): SemanticCapture<SemanticDependencySnapshot> {
        when (val admitted = admit(roots, budget)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
        val active =
            when (val captured = graph(budget, captureGraph)) {
                is Refinement.Refined -> captured.value
                is Refinement.Rejected -> return captured
            }
        val closure =
            when (val admitted = domain(active.graph).closure(roots)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return captureRejected(SemanticDependencyCaptureFailure.DEPENDENCY_CLOSURE_REJECTED)
            }
        val selected = mutableListOf<NativeModuleInputs>()
        for (identity in closure.modules.sortedBy { it.value }) when (
            val captured = module(active, closure, identity, budget, captureModule)
        ) {
            is Refinement.Refined -> selected += captured.value
            is Refinement.Rejected -> return captured
        }
        return snapshot(closure, selected)
    }

    private fun admit(roots: Set<WorkspaceModuleIdentity>, budget: DependencyCaptureBudget): SemanticCapture<Unit> {
        if (state == State.Closed) return captureRejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED)
        if (roots.isEmpty() || !modules.containsAll(roots))
            return captureRejected(SemanticDependencyCaptureFailure.DEPENDENCY_CLOSURE_REJECTED)
        if (authority.withCurrentOwner { Unit } is Refinement.Rejected)
            return captureRejected(SemanticDependencyCaptureFailure.AUTHORITY_MOVED)
        return budget.current()
    }

    private fun graph(
        budget: DependencyCaptureBudget,
        capture: () -> SemanticCapture<Graph>,
    ): SemanticCapture<State.Active<Graph>> =
        when (val selected = state) {
            State.Closed -> captureRejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED)
            is State.Unavailable -> {
                budget.observation.count(IntellijReadCounter.DEPENDENCY_GRAPH_UNAVAILABLE_MEMO_HITS)
                captureRejected(selected.cause)
            }
            is State.Active -> {
                budget.observation.count(IntellijReadCounter.DEPENDENCY_GRAPH_MEMO_HITS)
                Refinement.Refined(selected)
            }
            State.Pending ->
                when (val captured = capture()) {
                    is Refinement.Rejected -> captured.also { state = State.Unavailable(it.failure) }
                    is Refinement.Refined ->
                        if (domain(captured.value).model !== model)
                            captureRejected(SemanticDependencyCaptureFailure.MODEL_ROOT_MISMATCH)
                        else Refinement.Refined(State.Active(captured.value).also { state = it })
                }
        }

    private fun module(
        active: State.Active<Graph>,
        closure: SemanticDependencyClosure,
        identity: WorkspaceModuleIdentity,
        budget: DependencyCaptureBudget,
        capture: (Graph, SemanticDependencyClosure, WorkspaceModuleIdentity) -> SemanticCapture<NativeModuleInputs>,
    ): SemanticCapture<NativeModuleInputs> {
        when (val admitted = budget.current()) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
        active.modules[identity]?.let { cached ->
            budget.observation.count(
                when (cached) {
                    is Refinement.Refined -> IntellijReadCounter.DEPENDENCY_MODULE_INPUT_MEMO_HITS
                    is Refinement.Rejected -> IntellijReadCounter.DEPENDENCY_MODULE_INPUT_UNAVAILABLE_MEMO_HITS
                }
            )
            return cached
        }
        val captured =
            when (val observed = capture(active.graph, closure, identity)) {
                is Refinement.Rejected -> observed
                is Refinement.Refined ->
                    if (observed.value.sources.admitOwner(closure.graph, identity) is Refinement.Rejected)
                        captureRejected(SemanticDependencyCaptureFailure.SOURCE_MODULE_INVENTORY_REJECTED)
                    else observed
            }
        active.modules[identity] = captured
        return captured
    }

    private fun snapshot(
        closure: SemanticDependencyClosure,
        selected: List<NativeModuleInputs>,
    ): SemanticCapture<SemanticDependencySnapshot> {
        val inventory =
            when (val admitted = SemanticDependencyInventory.admit(closure, selected.map { it.sources })) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return captureRejected(SemanticDependencyCaptureFailure.SOURCE_INVENTORY_REJECTED)
            }
        val inputs =
            when (
                val admitted =
                    SemanticResolutionInputInventory.fromCompiler(
                        closure,
                        selected.associate { it.sources.module to it.inputs },
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return captureRejected(SemanticDependencyCaptureFailure.RESOLUTION_INPUT_INVENTORY_REJECTED)
            }
        return when (val admitted = SemanticDependencySnapshot.fromCompiler(authority, inventory, inputs)) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected ->
                captureRejected(
                    when (admitted.failure) {
                        SemanticSnapshotAdmissionFailure.WorkspaceMismatch ->
                            SemanticDependencyCaptureFailure.MODEL_ROOT_MISMATCH
                        SemanticSnapshotAdmissionFailure.ResolutionInputDomainMismatch ->
                            SemanticDependencyCaptureFailure.RESOLUTION_INPUT_INVENTORY_REJECTED
                        is SemanticSnapshotAdmissionFailure.Authority ->
                            SemanticDependencyCaptureFailure.AUTHORITY_MOVED
                    }
                )
        }
    }

    fun finishNativeRead() {
        state = State.Closed
    }
}

internal class NativeModuleInputs(val sources: CompleteSemanticModuleSources, val inputs: SemanticResolutionInputs)
