package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.PositiveLimitFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureFailure
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

internal fun interface HostedCallbackDependencyCapture {
    fun capture(
        roots: Set<WorkspaceModuleIdentity>,
        budget: ResourceBudget,
    ): Refinement<SemanticDependencySnapshot, SemanticDependencyCaptureFailure>

    fun finishNativeRead() = Unit
}

/** All successful snapshots and projections are owned by one exact native attempt; failures are scoped by universe. */
internal class HostedReadCallbackPartitions(
    private val model: WorkspaceSearchScopeModel,
    private val attempts: HostedCallbackDependencyAttempts,
    private val allowance: HostedCallbackCaptureAllowance,
    private val currentBudget: () -> Refinement<ResourceBudget, PositiveLimitFailure>,
    private val observation: IntellijReadObservation,
    private val capture: HostedCallbackDependencyCapture,
) : HostedCallbackDependencyPartitions {
    private val modules = model.sourceRoots.mapTo(linkedSetOf(), ModelOwnedSourceRoot::module)
    private val captured = linkedMapOf<HostedCallbackDependencyUniverse, HostedCapturedCallbackPartitions>()
    private var lifetime = Lifetime.ACTIVE

    @Synchronized
    override fun forward(endpoint: RelationEndpoint): HostedCallbackPartition = observed { forwardCurrent(endpoint) }

    private fun forwardCurrent(endpoint: RelationEndpoint): HostedCallbackPartition {
        if (lifetime == Lifetime.CLOSED)
            return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.NativeReadEnded)
        val universe =
            when (val admitted = model.callbackUniverse(endpoint)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return HostedCallbackPartition.Rejected(admitted.failure)
            }
        val current =
            when (val admitted = current()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return HostedCallbackPartition.Rejected(admitted.failure)
            }
        val shared = captured.values.firstOrNull { universe.module in it.snapshot.inventory.closure.modules }
        if (shared != null) {
            observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_CAPTURES_SHARED)
            return shared.forward(endpoint)
        }
        return when (val selected = capture(universe, setOf(universe.module), current)) {
            is Refinement.Refined -> selected.value.forward(endpoint)
            is Refinement.Rejected -> HostedCallbackPartition.Rejected(selected.failure)
        }
    }

    @Synchronized override fun whole(): HostedCallbackPartition = observed { wholeCurrent() }

    private fun wholeCurrent(): HostedCallbackPartition {
        val current =
            when (val admitted = current()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return HostedCallbackPartition.Rejected(admitted.failure)
            }
        val shared =
            captured[HostedCallbackDependencyUniverse.WholeWorkspace]
                ?: captured.values.firstOrNull {
                    it.snapshot.inventory.closure.modules == it.snapshot.inventory.closure.graph.modules
                }
        if (shared != null) {
            val graph = shared.snapshot.inventory.closure.graph
            observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_CAPTURES_SHARED)
            if (shared.snapshot.inventory.closure.roots == graph.modules)
                return HostedCallbackPartition.Available(shared.snapshot)
            val closure =
                when (val admitted = graph.closure(graph.modules)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return HostedCallbackPartition.Rejected(
                            HostedCallbackPartitionFailure.Dependency(admitted.failure)
                        )
                }
            return shared.project(closure).also { projected ->
                if (projected is HostedCallbackPartition.Available)
                    captured[HostedCallbackDependencyUniverse.WholeWorkspace] =
                        HostedCapturedCallbackPartitions(projected.snapshot)
            }
        }
        return when (val selected = capture(HostedCallbackDependencyUniverse.WholeWorkspace, modules, current)) {
            is Refinement.Refined -> HostedCallbackPartition.Available(selected.value.snapshot)
            is Refinement.Rejected -> HostedCallbackPartition.Rejected(selected.failure)
        }
    }

    private fun current(): Refinement<ResourceBudget, HostedCallbackPartitionFailure> {
        if (lifetime == Lifetime.CLOSED) return Refinement.Rejected(HostedCallbackPartitionFailure.NativeReadEnded)
        return when (val selected = currentBudget()) {
            is Refinement.Refined -> selected
            is Refinement.Rejected -> Refinement.Rejected(HostedCallbackPartitionFailure.ParentBudget(selected.failure))
        }
    }

    private inline fun observed(select: () -> HostedCallbackPartition): HostedCallbackPartition =
        select().also {
            if (it is HostedCallbackPartition.Rejected)
                observation.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_PARTITION_REJECTIONS)
        }

    private fun capture(
        universe: HostedCallbackDependencyUniverse,
        roots: Set<WorkspaceModuleIdentity>,
        current: ResourceBudget,
    ): Refinement<HostedCapturedCallbackPartitions, HostedCallbackPartitionFailure> {
        val budget =
            when (val admitted = allowance.remaining(current)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(HostedCallbackPartitionFailure.PreparationBudget(admitted.failure))
            }
        return when (val admitted = attempts.capture(universe) { capture.capture(roots, budget) }) {
            is Refinement.Refined -> {
                if (
                    admitted.value.inventory.closure.roots != roots ||
                        admitted.value.inventory.closure.graph.model != model
                )
                    return Refinement.Rejected(HostedCallbackPartitionFailure.CaptureUniverseMismatch)
                val detached = HostedCapturedCallbackPartitions(admitted.value)
                captured[universe] = detached
                Refinement.Refined(detached)
            }
            is Refinement.Rejected -> Refinement.Rejected(HostedCallbackPartitionFailure.Capture(admitted.failure))
        }
    }

    @Synchronized
    override fun finishNativeRead() {
        if (lifetime == Lifetime.CLOSED) return
        lifetime = Lifetime.CLOSED
        captured.clear()
        capture.finishNativeRead()
    }

    private enum class Lifetime {
        ACTIVE,
        CLOSED,
    }
}
