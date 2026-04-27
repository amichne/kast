package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.standalone.workspace.WorkspaceDiscoveryDiagnostics

internal fun buildDependentModuleNamesBySourceModuleName(
    sourceModules: List<SourceModuleSpec>,
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

internal data class WorkspaceLayout(
    val sourceModules: List<SourceModuleSpec>,
    val diagnostics: WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(),
    val dependentModuleNamesBySourceModuleName: Map<ModuleName, Set<ModuleName>> = emptyMap(),
)
