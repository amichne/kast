package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageInputFingerprint
import io.github.amichne.kast.indexstore.api.index.FileStageOutcome
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.PendingFileStage
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

internal fun workspaceSourceRawPath(workspaceRoot: Path, path: String): String =
    workspaceSourcePath(workspaceRoot, path).rawPath

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
): FileStageOutcome? = fileStageOutcome(requireWorkspaceSourcePath(path), stage)

internal fun SqliteSourceIndexStore.fileStageScopeCoverage(
    stage: FileIndexStage,
    path: String,
): FileStageScopeCoverage = fileStageScopeCoverage(stage, requireWorkspaceSourcePath(path))

internal fun SqliteSourceIndexStore.fileStageScopeCoverage(
    stage: FileIndexStage,
    paths: Collection<String>,
): FileStageScopeCoverage = fileStageScopeCoverage(stage, paths.map(::requireWorkspaceSourcePath))

internal fun SqliteSourceIndexStore.pendingFileStage(
    path: String,
    contentHash: FileContentHash,
    stage: FileIndexStage,
    version: FileStageVersion,
    inputFingerprint: FileStageInputFingerprint? = null,
): PendingFileStage? = pendingFileStage(
    path = requireWorkspaceSourcePath(path),
    contentHash = contentHash,
    stage = stage,
    version = version,
    inputFingerprint = inputFingerprint,
)

internal fun SqliteSourceIndexStore.reconcileRemovedFileInventory(paths: Collection<String>) =
    reconcileRemovedFileInventory(paths.map(::requireWorkspaceSourcePath))

private fun SqliteSourceIndexStore.requireWorkspaceSourcePath(path: String): WorkspaceSourcePath =
    checkNotNull(sourcePath(Path.of(path))) { "Test source path is outside the store workspace root: $path" }
