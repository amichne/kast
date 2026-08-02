package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleIdentity
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleName
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import java.nio.file.Path
import java.sql.ResultSet

internal fun ResultSet.getNullableInt(column: Int): Int? =
    getObject(column)?.let { (it as Number).toInt() }

internal fun SqliteSourceIndexStoreState.requireWorkspaceSourcePath(absolutePath: String): WorkspaceSourcePath =
    checkNotNull(sourceFilePolicy.sourcePath(Path.of(absolutePath))) {
        "Persisted source path is outside the exact workspace root or is not an eligible Kotlin source file: $absolutePath"
    }

internal fun SqliteSourceIndexStoreState.requireWorkspaceSourcePath(path: WorkspaceSourcePath): WorkspaceSourcePath {
    require(path.workspaceRoot == normalizedWorkspaceRoot) {
        "Workspace source proof belongs to a different workspace root: " +
            "expected ${normalizedWorkspaceRoot.value}, received ${path.workspaceRoot.value}"
    }
    return path
}

internal fun WorkspaceSourcePath.toDatabasePath(): String = absolute.value.value

internal fun SourceIndexModuleIdentity.toDatabaseModuleName(): String =
    sourceSet?.let { "${name.value}[${it.value}]" } ?: name.value

internal fun decodeSourceIndexModuleIdentity(
    moduleName: String?,
    sourceSet: String?,
): SourceIndexModuleIdentity? {
    if (moduleName == null) {
        check(sourceSet == null) { "Persisted source set requires a module name" }
        return null
    }
    val parsedSourceSet = sourceSet?.let(GradleSourceSetName::parse)
    val sourceSetSuffix = parsedSourceSet?.let { "[${it.value}]" }
    val baseName = sourceSetSuffix
        ?.takeIf(moduleName::endsWith)
        ?.let(moduleName::removeSuffix)
        ?: moduleName
    return SourceIndexModuleIdentity(
        name = SourceIndexModuleName.parse(baseName),
        sourceSet = parsedSourceSet,
    )
}
