package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.SourceIndexSnapshot
import java.nio.file.Path

internal enum class KastRecentFileIndexState(
    val displayName: String,
) {
    INDEXED("Indexed"),
    MISSING_FROM_INDEX("Not indexed"),
    INDEX_UNAVAILABLE("Database unavailable"),
}

internal data class KastRecentFileIndexRow(
    val displayPath: String,
    val absolutePath: String,
    val state: KastRecentFileIndexState,
    val moduleName: String?,
    val packageName: String?,
    val identifierCount: Int?,
    val importCount: Int?,
    val wildcardImportCount: Int?,
)

internal fun recentFileIndexRows(
    recentFilePaths: List<Path>,
    workspaceRoot: Path,
    snapshot: SourceIndexSnapshot?,
): List<KastRecentFileIndexRow> {
    val root = workspaceRoot.toAbsolutePath().normalize()
    val identifierCountByPath = snapshot?.identifierCountByPath().orEmpty()
    val indexedPaths = snapshot?.indexedPaths().orEmpty()
    return recentFilePaths
        .map { path -> path.toAbsolutePath().normalize() }
        .distinct()
        .map { path ->
            val key = path.toString()
            KastRecentFileIndexRow(
                displayPath = path.displayPath(root),
                absolutePath = key,
                state = when {
                    snapshot == null -> KastRecentFileIndexState.INDEX_UNAVAILABLE
                    key in indexedPaths -> KastRecentFileIndexState.INDEXED
                    else -> KastRecentFileIndexState.MISSING_FROM_INDEX
                },
                moduleName = snapshot?.moduleNameByPath?.get(key),
                packageName = snapshot?.packageByPath?.get(key),
                identifierCount = identifierCountByPath[key],
                importCount = snapshot?.importsByPath?.get(key)?.size,
                wildcardImportCount = snapshot?.wildcardImportPackagesByPath?.get(key)?.size,
            )
        }
}

private fun SourceIndexSnapshot.indexedPaths(): Set<String> = buildSet {
    addAll(moduleNameByPath.keys)
    addAll(packageByPath.keys)
    addAll(importsByPath.keys)
    addAll(wildcardImportPackagesByPath.keys)
    candidatePathsByIdentifier.values.forEach(::addAll)
}

private fun SourceIndexSnapshot.identifierCountByPath(): Map<String, Int> = buildMap {
    candidatePathsByIdentifier.values
        .flatten()
        .forEach { path -> put(path, getOrDefault(path, 0) + 1) }
}

private fun Path.displayPath(workspaceRoot: Path): String =
    if (startsWith(workspaceRoot)) {
        workspaceRoot.relativize(this).joinToString("/")
    } else {
        toString()
    }
