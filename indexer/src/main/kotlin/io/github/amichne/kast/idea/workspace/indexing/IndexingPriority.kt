package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.WorkspaceRelativePath
import io.github.amichne.kast.api.client.fields.WorkspaceIndexingPattern
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleIdentity
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleName
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath

internal data class IndexedSourceIdentifiers(
    val paths: List<WorkspaceSourcePath>,
    val criticalPaths: Set<WorkspaceSourcePath>,
    val unmatchedCriticalPatterns: List<WorkspaceIndexingPattern>,
    val removedPaths: List<WorkspaceSourcePath> = emptyList(),
)

internal data class IndexingPriorityEntry(
    val path: WorkspaceSourcePath,
    val module: SourceIndexModuleIdentity?,
)

internal fun prioritizeIndexingPaths(
    pathsByModule: Collection<IndexingPriorityEntry>,
    moduleOrder: List<SourceIndexModuleName>,
    criticalPaths: Set<WorkspaceSourcePath>,
): List<WorkspaceSourcePath> {
    val modulePriorityByName = moduleOrder
        .withIndex()
        .associate { (index, moduleName) -> moduleName to index }

    fun modulePriority(module: SourceIndexModuleIdentity?): Int = module?.name
        ?.let(modulePriorityByName::get)
        ?: Int.MAX_VALUE

    return pathsByModule
        .sortedWith(
            compareBy<IndexingPriorityEntry>(
                { entry -> if (entry.path in criticalPaths) 0 else 1 },
                { entry -> sourceSetPriority(entry.module?.sourceSet) },
                { entry -> modulePriority(entry.module) },
                { entry -> entry.module?.name?.value.orEmpty() },
                IndexingPriorityEntry::path,
            ),
        ).map(IndexingPriorityEntry::path)
}

private fun sourceSetPriority(sourceSet: GradleSourceSetName?): Int =
    when (sourceSet?.value) {
        "main" -> 0
        "testFixtures" -> 1
        "test" -> 2
        else -> 3
    }

internal fun indexedModuleIdentityForFilePath(
    ideaModule: IdeaWorkspaceModuleIdentity,
    filePath: WorkspaceSourcePath,
    sourceSet: GradleSourceSetName?,
): SourceIndexModuleIdentity = SourceIndexModuleIdentity(
    name = legacyGradleProjectPathForFile(filePath) ?: SourceIndexModuleName.parse(ideaModule.value),
    sourceSet = sourceSet,
)

internal fun legacyGradleProjectPathForFile(
    filePath: WorkspaceSourcePath,
): SourceIndexModuleName? = legacyGradleProjectPathForWorkspacePath(filePath.relative)

internal fun legacyGradleProjectPathForWorkspacePath(
    relativePath: WorkspaceRelativePath,
): SourceIndexModuleName? {
    val segments = relativePath.path.map { segment -> segment.toString() }
    val srcIndex = segments.indexOf("src")
    if (srcIndex < 0) return null

    val projectSegments = segments.take(srcIndex)
    val value = if (projectSegments.isEmpty()) ":" else projectSegments.joinToString(
        separator = ":",
        prefix = ":",
    )
    return SourceIndexModuleName.parse(value)
}
