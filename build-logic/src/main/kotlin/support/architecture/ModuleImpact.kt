package support.architecture

import java.util.Collections

internal sealed interface ModuleImpactFailure {
    data object EmptyGraph : ModuleImpactFailure
    data class MissingEndpoint(val edge: ProjectDependencyObservation) : ModuleImpactFailure
    data object EmptySelection : ModuleImpactFailure
    data class UnknownModule(val path: String) : ModuleImpactFailure
    data class DuplicateSelection(val module: ModuleId) : ModuleImpactFailure
}

internal sealed interface ModuleImpactAdmission {
    data class Accepted(val impact: ModuleImpact) : ModuleImpactAdmission
    data class Rejected(val failure: ModuleImpactFailure) : ModuleImpactAdmission
}

/** A selected domain in one detached graph of declared dependencies. No compiler or effect authority follows. */
internal class ModuleImpact private constructor(
    val modules: Set<ModuleId>,
    val dependencies: Set<ProjectDependencyObservation>,
    val selected: Set<ModuleId>,
) {
    /** Each path starts at a consumer and ends at a selected dependency. Selected modules are separate. */
    fun dependencyPaths(): List<List<ModuleId>> {
        val consumers = dependencies.groupBy({ it.dependency }, { it.consumer })
        val paths = selected.associateWith { listOf(it) }.toMutableMap()
        val pending = ArrayDeque(selected)
        while (pending.isNotEmpty()) {
            val dependency = pending.removeFirst()
            for (consumer in consumers[dependency].orEmpty().sortedBy(ModuleId::projectPath)) {
                if (consumer !in paths) {
                    paths[consumer] = listOf(consumer) + paths.getValue(dependency)
                    pending.addLast(consumer)
                }
            }
        }
        return paths.keys.filterNot(selected::contains).sortedBy(ModuleId::projectPath).map(paths::getValue)
    }

    companion object {
        fun admit(graph: ObservedProjectGraph, rawSelection: List<String>): ModuleImpactAdmission {
            if (graph.modules.isEmpty()) return ModuleImpactAdmission.Rejected(ModuleImpactFailure.EmptyGraph)
            val modules = graph.modules.sortedBy(ModuleId::projectPath)
            val dependencies = graph.projectDependencies.sortedWith(
                compareBy({ it.consumer.projectPath }, { it.dependency.projectPath }),
            )
            for (edge in dependencies) {
                if (edge.consumer !in graph.modules || edge.dependency !in graph.modules) {
                    return ModuleImpactAdmission.Rejected(ModuleImpactFailure.MissingEndpoint(edge))
                }
            }
            if (rawSelection.isEmpty()) return ModuleImpactAdmission.Rejected(ModuleImpactFailure.EmptySelection)
            val byPath = modules.associateBy(ModuleId::projectPath)
            val selected = linkedSetOf<ModuleId>()
            for (path in rawSelection) {
                val module = byPath[path]
                    ?: return ModuleImpactAdmission.Rejected(ModuleImpactFailure.UnknownModule(path))
                if (!selected.add(module)) {
                    return ModuleImpactAdmission.Rejected(ModuleImpactFailure.DuplicateSelection(module))
                }
            }
            return ModuleImpactAdmission.Accepted(
                ModuleImpact(
                    Collections.unmodifiableSet(LinkedHashSet(modules)),
                    Collections.unmodifiableSet(LinkedHashSet(dependencies)),
                    Collections.unmodifiableSet(LinkedHashSet(selected.sortedBy(ModuleId::projectPath))),
                ),
            )
        }
    }
}
