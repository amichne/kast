package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.standalone.workspace.WorkspaceDiscoveryDiagnostics

internal fun buildDependentModuleNamesBySourceModuleName(
    sourceModules: List<StandaloneSourceModuleSpec>,
): Map<ModuleName, Set<ModuleName>> {
    val reverseDependencies = linkedMapOf<ModuleName, MutableSet<ModuleName>>()
    sourceModules.forEach { sourceModule ->
        sourceModule.dependencyModuleNames.forEach { dependencyModuleName ->
            reverseDependencies.getOrPut(dependencyModuleName) { linkedSetOf() }.add(sourceModule.name)
        }
    }

    return sourceModules.associate { sourceModule ->
        val visitedModuleNames = linkedSetOf(sourceModule.name)
        val pendingModuleNames = ArrayDeque(listOf(sourceModule.name))
        while (pendingModuleNames.isNotEmpty()) {
            val currentModuleName = pendingModuleNames.removeFirst()
            reverseDependencies[currentModuleName].orEmpty().forEach { dependentModuleName ->
                if (visitedModuleNames.add(dependentModuleName)) {
                    pendingModuleNames += dependentModuleName
                }
            }
        }
        sourceModule.name to visitedModuleNames.toSet()
    }
}

private fun StandaloneSourceModuleSpec?.orEmptyDependencyModuleNames(): List<ModuleName> =
    this?.dependencyModuleNames.orEmpty()

@Suppress("UNUSED_PARAMETER")
internal fun computeModulePriorityOrder(
    activeModule: ModuleName?,
    moduleSpecs: List<StandaloneSourceModuleSpec>,
    dependentModuleGraph: Map<ModuleName, Set<ModuleName>>,
    depth: Int,
): List<String> {
    val topologicalModuleNames = topologicallySortSourceModules(moduleSpecs).map(StandaloneSourceModuleSpec::name)
    if (activeModule == null) {
        return topologicalModuleNames.map(ModuleName::value)
    }

    val modulesByName = moduleSpecs.associateBy(StandaloneSourceModuleSpec::name)
    if (activeModule !in modulesByName) {
        return topologicalModuleNames.map(ModuleName::value)
    }
    val priorityNames = linkedSetOf<ModuleName>()
    val pending = ArrayDeque<Pair<ModuleName, Int>>()
    pending += activeModule to 0
    while (pending.isNotEmpty()) {
        val (moduleName, distance) = pending.removeFirst()
        if (!priorityNames.add(moduleName) || distance >= depth) {
            continue
        }
        modulesByName[moduleName]
            .orEmptyDependencyModuleNames()
            .sortedBy(ModuleName::value)
            .forEach { dependencyName -> pending += dependencyName to distance + 1 }
    }

    return (priorityNames + topologicalModuleNames.filterNot(priorityNames::contains))
        .map(ModuleName::value)
}

internal data class StandaloneWorkspaceLayout(
    val sourceModules: List<StandaloneSourceModuleSpec>,
    val diagnostics: WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(),
    val dependentModuleNamesBySourceModuleName: Map<ModuleName, Set<ModuleName>> = emptyMap(),
)
