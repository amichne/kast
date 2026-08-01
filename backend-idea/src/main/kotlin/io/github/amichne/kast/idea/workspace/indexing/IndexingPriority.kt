package io.github.amichne.kast.idea

import java.nio.file.Path

internal data class IndexedSourceIdentifiers(
    val paths: List<String>,
    val criticalPaths: Set<String>,
    val unmatchedCriticalPatterns: List<String>,
)

internal fun prioritizeIndexingPaths(
    pathsByModule: Collection<Pair<String, String?>>,
    moduleOrder: List<String>,
    criticalPaths: Set<String>,
): List<String> {
    val modulePriorityByName = moduleOrder
        .withIndex()
        .associate { (index, moduleName) -> moduleName to index }

    fun canonicalModuleName(moduleName: String?): String? = moduleName?.substringBefore("[")
    fun modulePriority(moduleName: String?): Int = canonicalModuleName(moduleName)
        ?.let(modulePriorityByName::get)
        ?: Int.MAX_VALUE

    return pathsByModule
        .sortedWith(
            compareBy<Pair<String, String?>>(
                { (path) -> if (path in criticalPaths) 0 else 1 },
                { (path) -> sourceSetPriority(path) },
                { (_, moduleName) -> modulePriority(moduleName) },
                { (_, moduleName) -> canonicalModuleName(moduleName) ?: "" },
                { (path) -> path },
            ),
        ).map(Pair<String, String?>::first)
}

private fun sourceSetPriority(path: String): Int {
    val normalized = "/${path.replace('\\', '/').trim('/')}/"
    return when {
        "/src/main/" in normalized -> 0
        "/src/testFixtures/" in normalized -> 1
        "/src/test/" in normalized -> 2
        else -> 3
    }
}

internal fun indexedModuleNameForFilePath(
    ideaModuleName: String,
    filePath: String,
    workspaceRoot: Path,
    sourceSet: String?,
): String {
    val modulePath = legacyGradleProjectPathForFile(filePath, workspaceRoot) ?: ideaModuleName
    return if (sourceSet == null) modulePath else "$modulePath[$sourceSet]"
}

internal fun legacyGradleProjectPathForFile(
    filePath: String,
    workspaceRoot: Path,
): String? {
    val root = workspaceRoot.toAbsolutePath().normalize()
    val path = Path.of(filePath).toAbsolutePath().normalize()
    if (!path.startsWith(root)) return null

    val segments = root.relativize(path).map { segment -> segment.toString() }
    val srcIndex = segments.indexOf("src")
    if (srcIndex < 0) return null

    val projectSegments = segments.take(srcIndex)
    return if (projectSegments.isEmpty()) ":" else projectSegments.joinToString(
        separator = ":",
        prefix = ":",
    )
}
