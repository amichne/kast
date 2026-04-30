package io.github.amichne.kast.intellij

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.indexstore.ReferenceIndexer
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import io.github.amichne.kast.shared.analysis.PsiReferenceScanner
import io.github.amichne.kast.shared.analysis.PsiSourceIndexScanner
import java.nio.file.Files
import java.nio.file.Path

internal class IntelliJProjectIndexer(
    private val project: Project,
    private val workspaceRoot: Path,
    private val store: SqliteSourceIndexStore,
    private val cancelled: () -> Boolean,
) {
    private val environment = IntelliJReferenceIndexEnvironment(
        project = project,
        workspaceRoot = workspaceRoot,
        cancelled = cancelled,
    )

    fun indexProject(config: KastConfig) {
        store.ensureSchema()
        val currentFilePaths = indexSourceIdentifiers()
        if (config.indexing.phase2Enabled && !environment.isCancelled()) {
            indexReferences(currentFilePaths, config.indexing.referenceBatchSize)
        }
    }

    fun indexSourceIdentifiers(): Collection<String> {
        store.ensureSchema()
        val scanner = PsiSourceIndexScanner(
            environment = environment,
            moduleNameForFile = ::moduleNameForFile,
        )
        val updates = environment.allFilePaths().mapNotNull(scanner::scanFile)
        val manifest = updates.associate { update ->
            update.path to lastModifiedMillis(update.path)
        }
        store.saveFullIndex(updates = updates, manifest = manifest)
        return manifest.keys
    }

    private fun indexReferences(
        currentFilePaths: Collection<String>,
        referenceBatchSize: Int,
    ) {
        store.removeReferencesOutsideSources(currentFilePaths)
        ReferenceIndexer(store, batchSize = referenceBatchSize).indexReferences(
            filePaths = currentFilePaths,
            referenceScanner = PsiReferenceScanner(environment)::scanFileReferences,
            isCancelled = environment::isCancelled,
        )
    }

    private fun moduleNameForFile(psiFile: PsiFile): String? {
        val virtualFile = psiFile.virtualFile
        val module = ModuleUtilCore.findModuleForFile(virtualFile, project) ?: return null
        val sourceSet = sourceSetForFile(virtualFile.path)
        return if (sourceSet == null) module.name else "${module.name}[$sourceSet]"
    }

    private fun sourceSetForFile(path: String): String? {
        val normalizedPath = path.replace('\\', '/')
        return when {
            "/src/main/" in normalizedPath -> "main"
            "/src/testFixtures/" in normalizedPath -> "testFixtures"
            "/src/test/" in normalizedPath -> "test"
            else -> {
                val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(Path.of(path)) ?: return null
                ProjectFileIndex.getInstance(project).getSourceRootForFile(virtualFile)?.name
            }
        }
    }

    private fun lastModifiedMillis(filePath: String): Long {
        val path = Path.of(filePath)
        return if (Files.isRegularFile(path)) Files.getLastModifiedTime(path).toMillis() else 0L
    }
}
