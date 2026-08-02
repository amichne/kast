package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageOutcome
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleIdentity
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleName
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.nio.file.Path

internal val WorkspaceSourcePath.rawPath: String
    get() = absolute.value.value

internal fun workspaceSourcePath(workspaceRoot: Path, path: String): WorkspaceSourcePath =
    checkNotNull(SourceIndexFilePolicy.forWorkspace(workspaceRoot).sourcePath(Path.of(path))) {
        "Test source path is outside the exact workspace root: $path"
    }

internal fun workspaceSourcePaths(workspaceRoot: Path, paths: Collection<String>): List<WorkspaceSourcePath> =
    paths.map { path -> workspaceSourcePath(workspaceRoot, path) }

internal fun fileInventoryEntry(
    workspaceRoot: Path,
    path: String,
    lastModifiedMillis: Long,
    contentHash: FileContentHash,
    moduleName: String?,
    sourceSet: String?,
): FileInventoryEntry {
    val parsedSourceSet = sourceSet?.let(GradleSourceSetName::parse)
    val module = moduleName?.let { encodedName ->
        val suffix = parsedSourceSet?.let { "[${it.value}]" }
        val baseName = suffix
            ?.takeIf(encodedName::endsWith)
            ?.let(encodedName::removeSuffix)
            ?: encodedName
        SourceIndexModuleIdentity(SourceIndexModuleName.parse(baseName), parsedSourceSet)
    }
    return FileInventoryEntry(
        path = workspaceSourcePath(workspaceRoot, path),
        lastModifiedMillis = lastModifiedMillis,
        contentHash = contentHash,
        module = module,
    )
}

internal fun SqliteSourceIndexStore.fileStageOutcome(
    path: String,
    stage: FileIndexStage,
): FileStageOutcome? = fileStageOutcome(
    checkNotNull(sourcePath(Path.of(path))) { "Test source path is outside the store workspace root: $path" },
    stage,
)
