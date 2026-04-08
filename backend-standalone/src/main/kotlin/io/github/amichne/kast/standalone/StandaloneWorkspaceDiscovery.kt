package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ModuleName
import io.github.amichne.kast.standalone.workspace.GradleWorkspaceDiscovery
import io.github.amichne.kast.standalone.workspace.PhasedDiscoveryResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

internal fun discoverStandaloneWorkspaceLayout(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
): StandaloneWorkspaceLayout {
    val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
    if (sourceRoots.isNotEmpty()) {
        return StandaloneWorkspaceLayout(
            sourceModules = listOf(
                StandaloneSourceModuleSpec(
                    name = ModuleName(moduleName),
                    sourceRoots = normalizeStandalonePaths(sourceRoots),
                    binaryRoots = normalizeStandalonePaths(classpathRoots),
                    dependencyModuleNames = emptyList(),
                ),
            ),
        )
    }
    if (looksLikeGradleWorkspace(normalizedWorkspaceRoot)) {
        return GradleWorkspaceDiscovery.discover(
            workspaceRoot = normalizedWorkspaceRoot,
            extraClasspathRoots = normalizeStandalonePaths(classpathRoots),
        )
    }

    return StandaloneWorkspaceLayout(
        sourceModules = listOf(
            StandaloneSourceModuleSpec(
                name = ModuleName(moduleName),
                sourceRoots = discoverSourceRoots(normalizedWorkspaceRoot),
                binaryRoots = normalizeStandalonePaths(classpathRoots),
                dependencyModuleNames = emptyList(),
            ),
        ),
    )
}

internal fun discoverStandaloneWorkspaceLayoutPhased(
    workspaceRoot: Path,
    sourceRoots: List<Path>,
    classpathRoots: List<Path>,
    moduleName: String,
): PhasedDiscoveryResult {
    val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
    if (sourceRoots.isNotEmpty() || !looksLikeGradleWorkspace(normalizedWorkspaceRoot)) {
        return PhasedDiscoveryResult(
            initialLayout = discoverStandaloneWorkspaceLayout(
                workspaceRoot = normalizedWorkspaceRoot,
                sourceRoots = sourceRoots,
                classpathRoots = classpathRoots,
                moduleName = moduleName,
            ),
            enrichmentFuture = null,
        )
    }

    return GradleWorkspaceDiscovery.discoverPhased(
        workspaceRoot = normalizedWorkspaceRoot,
        extraClasspathRoots = normalizeStandalonePaths(classpathRoots),
    )
}

internal fun topologicallySortSourceModules(sourceModules: List<StandaloneSourceModuleSpec>): List<StandaloneSourceModuleSpec> {
    val sourceModulesByName = sourceModules.associateBy(StandaloneSourceModuleSpec::name)
    val incomingEdges = sourceModules.associate { module ->
        module.name to module.dependencyModuleNames.toMutableSet()
    }.toMutableMap()
    val outgoingEdges = linkedMapOf<ModuleName, MutableSet<ModuleName>>()
    for (module in sourceModules) {
        for (dependencyName in module.dependencyModuleNames) {
            require(sourceModulesByName.containsKey(dependencyName)) {
                "The standalone workspace layout referenced an unknown source module dependency $dependencyName"
            }
            outgoingEdges.getOrPut(dependencyName) { linkedSetOf() }.add(module.name)
        }
    }

    val readyNames = ArrayDeque(
        sourceModules
            .filter { module -> incomingEdges.getValue(module.name).isEmpty() }
            .map(StandaloneSourceModuleSpec::name)
            .sortedBy { it.value },
    )
    val orderedModules = mutableListOf<StandaloneSourceModuleSpec>()
    while (readyNames.isNotEmpty()) {
        val moduleName = readyNames.removeFirst()
        orderedModules += checkNotNull(sourceModulesByName[moduleName])
        for (dependentName in outgoingEdges[moduleName].orEmpty().sortedBy { it.value }) {
            val dependencies = incomingEdges.getValue(dependentName)
            dependencies.remove(moduleName)
            if (dependencies.isEmpty()) {
                readyNames.addLast(dependentName)
            }
        }
    }

    require(orderedModules.size == sourceModules.size) {
        val unresolvedModuleNames = incomingEdges
            .filterValues(Set<ModuleName>::isNotEmpty)
            .keys
            .sortedBy { it.value }
        "The standalone workspace layout contains cyclic source module dependencies: ${
            unresolvedModuleNames.joinToString(
                ", "
            )
        }"
    }
    return orderedModules
}

private fun discoverSourceRoots(workspaceRoot: Path): List<Path> {
    val conventionalRoots = listOf(
        workspaceRoot.resolve("src/main/kotlin"),
        workspaceRoot.resolve("src/main/java"),
        workspaceRoot.resolve("src/test/kotlin"),
        workspaceRoot.resolve("src/test/java"),
    ).filter(Files::isDirectory)
    if (conventionalRoots.isNotEmpty()) {
        return conventionalRoots.map(::normalizeStandalonePath).distinct().sorted()
    }

    val discoveredRoots = linkedSetOf<Path>()
    Files.walk(workspaceRoot).use { paths ->
        paths
            .filter { path ->
                Files.isRegularFile(path) && path.extension in setOf("kt", "kts", "java")
            }
            .forEach { file -> discoveredRoots.add(normalizeStandalonePath(file.parent)) }
    }
    return discoveredRoots.toList().sorted()
}

private fun looksLikeGradleWorkspace(workspaceRoot: Path): Boolean = listOf(
    "settings.gradle.kts",
    "settings.gradle",
    "build.gradle.kts",
    "build.gradle",
).any { fileName -> Files.isRegularFile(workspaceRoot.resolve(fileName)) }
