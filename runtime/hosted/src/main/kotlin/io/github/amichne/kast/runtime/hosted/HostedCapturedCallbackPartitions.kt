package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.topology.contract.CompleteSemanticModuleSources
import io.github.amichne.kast.topology.contract.SemanticDependencyInventory
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import java.nio.file.Path

internal interface HostedCallbackDependencyPartitions {
    fun forward(endpoint: RelationEndpoint): HostedCallbackPartition

    fun whole(): HostedCallbackPartition

    fun finishNativeRead() = Unit
}

/** Detached projection keeps the original compiler-visible graph and complete selected inputs. */
internal class HostedCapturedCallbackPartitions(val snapshot: SemanticDependencySnapshot) :
    HostedCallbackDependencyPartitions {
    private val partitions = mutableMapOf<WorkspaceModuleIdentity, SemanticDependencySnapshot>()

    override fun whole(): HostedCallbackPartition = HostedCallbackPartition.Available(snapshot)

    @Synchronized
    override fun forward(endpoint: io.github.amichne.kast.relation.contract.RelationEndpoint): HostedCallbackPartition {
        val source =
            snapshot.inventory.files.singleOrNull {
                Path.of(snapshot.authority.workspaceRoot.value).resolve(it.path.value).toString() ==
                    endpoint.file.stableValue
            } ?: return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.SourceNotInventoried)
        partitions[source.root.module]?.let {
            return HostedCallbackPartition.Available(it)
        }
        val graph = snapshot.inventory.closure.graph
        val closure =
            when (val admitted = graph.closure(setOf(source.root.module))) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.Dependency(admitted.failure))
            }
        val projected = project(closure)
        if (projected is HostedCallbackPartition.Available) partitions[source.root.module] = projected.snapshot
        return projected
    }

    fun project(closure: io.github.amichne.kast.topology.contract.SemanticDependencyClosure): HostedCallbackPartition {
        val graph = snapshot.inventory.closure.graph
        val missing = closure.modules - snapshot.inventory.closure.modules
        if (missing.isNotEmpty())
            return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.UncapturedModules(missing))
        val completed = mutableListOf<CompleteSemanticModuleSources>()
        for (module in closure.modules) when (
            val admitted =
                CompleteSemanticModuleSources.fromCompiler(
                    graph,
                    module,
                    snapshot.inventory.files.filter { it.root.module == module },
                )
        ) {
            is Refinement.Refined -> completed += admitted.value
            is Refinement.Rejected ->
                return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.Inventory(admitted.failure))
        }
        val inventory =
            when (val admitted = SemanticDependencyInventory.admit(closure, completed)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.Inventory(admitted.failure))
            }
        val inputs =
            when (val admitted = snapshot.inputs.narrow(closure)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return HostedCallbackPartition.Rejected(
                        HostedCallbackPartitionFailure.ResolutionInputs(admitted.failure)
                    )
            }
        return when (val admitted = SemanticDependencySnapshot.fromCompiler(snapshot.authority, inventory, inputs)) {
            is Refinement.Refined -> HostedCallbackPartition.Available(admitted.value)
            is Refinement.Rejected ->
                HostedCallbackPartition.Rejected(HostedCallbackPartitionFailure.Snapshot(admitted.failure))
        }
    }
}
