package io.github.amichne.kast.topology.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import java.util.Collections

sealed interface SemanticDependencyFailure {
    data class MissingModules(val modules: Set<WorkspaceModuleIdentity>) : SemanticDependencyFailure

    data class UnknownModules(val modules: Set<WorkspaceModuleIdentity>) : SemanticDependencyFailure

    data object EmptySelection : SemanticDependencyFailure
}

/** Complete compiler-visible module adjacency. External library/configuration identity is guarded separately. */
class SemanticModuleDependencies
private constructor(
    val model: WorkspaceSearchScopeModel,
    private val dependencies: Map<WorkspaceModuleIdentity, Set<WorkspaceModuleIdentity>>,
) {
    val modules: Set<WorkspaceModuleIdentity> = Collections.unmodifiableSet(dependencies.keys.toSet())

    /** Includes the complete retained graph and model, not only the selected closure. */
    val retainedBytes: Long =
        4096L +
            dependencies.entries.sumOf { (module, targets) ->
                4096L + module.value.length.toLong() * 2L + targets.sumOf { 256L + it.value.length.toLong() * 2L }
            } +
            model.sourceRoots.sumOf { source ->
                4096L +
                    2L *
                        (source.module.value.length.toLong() +
                            source.sourceRoot.value.length.toLong() +
                            source.project.buildRoot.value.length.toLong() +
                            source.project.projectPath.value.length.toLong() +
                            source.sourceSet.value.length.toLong())
            }

    fun closure(roots: Set<WorkspaceModuleIdentity>): Refinement<SemanticDependencyClosure, SemanticDependencyFailure> {
        if (roots.isEmpty()) return Refinement.Rejected(SemanticDependencyFailure.EmptySelection)
        val unknown = roots - modules
        if (unknown.isNotEmpty()) return Refinement.Rejected(SemanticDependencyFailure.UnknownModules(unknown))
        val pending = ArrayDeque(roots)
        val visited = linkedSetOf<WorkspaceModuleIdentity>()
        while (pending.isNotEmpty()) {
            val module = pending.removeFirst()
            if (visited.add(module)) pending.addAll(dependencies.getValue(module))
        }
        return Refinement.Refined(SemanticDependencyClosure(this, roots, visited))
    }

    internal fun edgesFor(
        modules: Set<WorkspaceModuleIdentity>
    ): Map<WorkspaceModuleIdentity, Set<WorkspaceModuleIdentity>> = dependencies.filterKeys { it in modules }

    companion object {
        /**
         * Only the native module-resolution boundary supplies exhaustive adjacency, including explicit empty entries.
         */
        fun fromCompiler(
            model: WorkspaceSearchScopeModel,
            dependencies: Map<WorkspaceModuleIdentity, Set<WorkspaceModuleIdentity>>,
        ): Refinement<SemanticModuleDependencies, SemanticDependencyFailure> {
            val modules = model.sourceRoots.mapTo(linkedSetOf(), ModelOwnedSourceRoot::module)
            val missing = modules - dependencies.keys
            if (missing.isNotEmpty()) return Refinement.Rejected(SemanticDependencyFailure.MissingModules(missing))
            val unknown = (dependencies.keys + dependencies.values.flatten()) - modules
            if (unknown.isNotEmpty()) return Refinement.Rejected(SemanticDependencyFailure.UnknownModules(unknown))
            val detached = dependencies.mapValues { (_, values) -> Collections.unmodifiableSet(values.toSet()) }
            return Refinement.Refined(SemanticModuleDependencies(model, Collections.unmodifiableMap(detached)))
        }
    }
}

/**
 * Negative lookup coverage follows all compiler-visible modules, not only declarations returned by prior resolution.
 */
class SemanticDependencyClosure
internal constructor(
    val graph: SemanticModuleDependencies,
    roots: Set<WorkspaceModuleIdentity>,
    modules: Set<WorkspaceModuleIdentity>,
) {
    val roots: Set<WorkspaceModuleIdentity> = Collections.unmodifiableSet(roots.toSet())
    val modules: Set<WorkspaceModuleIdentity> = Collections.unmodifiableSet(modules.toSet())
    val sourceRoots: Set<ModelOwnedSourceRoot> =
        Collections.unmodifiableSet(graph.model.sourceRoots.filterTo(linkedSetOf()) { it.module in modules })

    internal fun sameDomain(other: SemanticDependencyClosure): Boolean =
        graph.model.workspaceRoot == other.graph.model.workspaceRoot &&
            roots == other.roots &&
            modules == other.modules &&
            sourceRoots == other.sourceRoots &&
            graph.edgesFor(modules) == other.graph.edgesFor(other.modules)
}
