package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.fields.RelationshipIndexingModulePriorityDepth

internal data class IdeaModuleSpec(
    val name: String,
    val dependencyModuleNames: List<String>,
)

internal fun mergeModuleSpecsByName(moduleSpecs: List<IdeaModuleSpec>): List<IdeaModuleSpec> =
    moduleSpecs
        .groupBy(IdeaModuleSpec::name)
        .map { (name, specs) ->
            IdeaModuleSpec(
                name = name,
                dependencyModuleNames = specs
                    .flatMap(IdeaModuleSpec::dependencyModuleNames)
                    .filterNot { dependencyName -> dependencyName == name }
                    .toSortedSet()
                    .toList(),
            )
        }
        .sortedBy(IdeaModuleSpec::name)

internal fun computeModulePriorityOrder(
    activeModule: String?,
    moduleSpecs: List<IdeaModuleSpec>,
    dependentModuleGraph: Map<String, Set<String>>,
    depth: RelationshipIndexingModulePriorityDepth,
): List<String> {
    val mergedModuleSpecs = mergeModuleSpecsByName(moduleSpecs)
    val moduleNames = mergedModuleSpecs.mapTo(mutableSetOf()) { it.name }.sorted()
    if (activeModule == null || activeModule !in moduleNames) {
        return topologicallySortModules(mergedModuleSpecs)
    }

    val priorityModules = linkedSetOf<String>()
    val queue: ArrayDeque<Pair<String, Int>> = ArrayDeque()
    queue += activeModule to 0
    while (queue.isNotEmpty()) {
        val (moduleName, moduleDepth) = queue.removeFirst()
        if (!priorityModules.add(moduleName) || moduleDepth >= depth.value) {
            continue
        }
        dependentModuleGraph[moduleName]
            .orEmpty()
            .sorted()
            .forEach { dependencyModuleName ->
                queue += dependencyModuleName to moduleDepth + 1
            }
    }

    return (priorityModules + topologicallySortModules(mergedModuleSpecs).filterNot { it in priorityModules }).toList()
}

private fun topologicallySortModules(moduleSpecs: List<IdeaModuleSpec>): List<String> {
    val mergedModuleSpecs = mergeModuleSpecsByName(moduleSpecs)
    val modulesByName = mergedModuleSpecs.associateBy(IdeaModuleSpec::name)
    val incomingEdges = mergedModuleSpecs
        .associate { spec -> spec.name to spec.dependencyModuleNames.toMutableSet() }
        .toMutableMap()

    val outgoingEdges = linkedMapOf<String, MutableSet<String>>()
    for (spec in mergedModuleSpecs) {
        for (dependencyName in spec.dependencyModuleNames) {
            if (!modulesByName.containsKey(dependencyName)) {
                continue
            }
            outgoingEdges
                .getOrPut(dependencyName) { linkedSetOf() }
                .add(spec.name)
        }
    }

    val readyNames = ArrayDeque(
        mergedModuleSpecs
            .filter { spec -> incomingEdges.getValue(spec.name).isEmpty() }
            .map(IdeaModuleSpec::name)
            .sorted(),
    )
    val ordered = mutableListOf<String>()
    while (readyNames.isNotEmpty()) {
        val moduleName = readyNames.removeFirst()
        ordered += moduleName
        for (dependentName in outgoingEdges[moduleName].orEmpty().sorted()) {
            val dependencies = incomingEdges.getValue(dependentName)
            dependencies.remove(moduleName)
            if (dependencies.isEmpty()) {
                readyNames.addLast(dependentName)
            }
        }
    }

    return ordered
}
