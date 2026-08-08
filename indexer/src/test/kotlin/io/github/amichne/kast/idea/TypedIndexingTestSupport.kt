package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
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

internal fun authoredGradleSourceRoot(path: Path): IdeaGradleProjectLoadBridge.GradleSourceRoot =
    IdeaGradleProjectLoadBridge.classifySourceRoot(path, listOf(ExternalSystemSourceType.SOURCE))

internal fun generatedGradleSourceRoot(path: Path): IdeaGradleProjectLoadBridge.GradleSourceRoot =
    IdeaGradleProjectLoadBridge.classifySourceRoot(path, listOf(ExternalSystemSourceType.SOURCE_GENERATED))

internal fun unknownGradleSourceRoot(path: Path): IdeaGradleProjectLoadBridge.GradleSourceRoot =
    IdeaGradleProjectLoadBridge.classifySourceRoot(path, emptyList())

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

/**
 * Test effect transition: `(Project, Document, String) -> committed PSI document state`.
 *
 * Owns the write action and PSI commit required after a test replaces the
 * document image; callers cannot accidentally observe an uncommitted edit.
 */
internal fun replaceProjectDocument(project: Project, document: Document, content: String) {
    val application = ApplicationManager.getApplication()
    application.invokeAndWait {
        application.runWriteAction { document.setText(content) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
