package io.github.amichne.kast.idea

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import io.github.amichne.kast.api.client.WorkspaceRelativePath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.SourceIndexModuleIdentity
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import java.nio.file.Path

internal class IdeaSourceIndexModuleResolver(
    private val project: Project,
    private val workspaceRoot: Path,
    private val sourceFilePolicy: WorkspaceSourceIndexFilePolicy,
) {
    fun discoverModuleSpecs(): List<IdeaModuleSpec> {
        val moduleSpecs = ModuleManager.getInstance(project).modules
            .sortedBy(::indexedModuleNameForModule)
            .map { module ->
                val rootManager = ModuleRootManager.getInstance(module)
                IdeaModuleSpec(
                    name = indexedModuleNameForModule(module),
                    dependencyModuleNames = rootManager.dependencies.map(::indexedModuleNameForModule).sorted(),
                )
            }
        return mergeModuleSpecsByName(moduleSpecs)
    }

    fun dependencyGraph(moduleSpecs: List<IdeaModuleSpec>): Map<String, Set<String>> =
        mergeModuleSpecsByName(moduleSpecs)
            .associate { module ->
                module.name to module.dependencyModuleNames.toSet()
            }

    fun moduleNameForFile(psiFile: PsiFile): String? = runIdeaReadAction {
        val virtualFile = psiFile.virtualFile
        val sourcePath = sourceFilePolicy.sourcePath(Path.of(virtualFile.path)) ?: return@runIdeaReadAction null
        moduleIdentityForFile(psiFile, sourcePath)?.toLegacyModuleName()
    }

    fun moduleIdentityForFile(
        psiFile: PsiFile,
        sourcePath: WorkspaceSourcePath,
    ): SourceIndexModuleIdentity? = runIdeaReadAction {
        val module = ModuleUtilCore.findModuleForFile(psiFile.virtualFile, project) ?: return@runIdeaReadAction null
        indexedModuleIdentityForFilePath(
            ideaModule = IdeaWorkspaceModuleIdentity.of(module.name),
            filePath = sourcePath,
            sourceSet = legacySourceSetLabelForFile(sourcePath),
        )
    }

    fun referenceIndexOwnersByPath(
        snapshot: IdeaWorkspaceFileInventorySnapshot,
    ): Map<WorkspaceSourcePath, Set<IdeaWorkspaceModuleIdentity>> {
        val ownersByPath = sortedMapOf<WorkspaceSourcePath, MutableSet<IdeaWorkspaceModuleIdentity>>()
        snapshot.modules.forEach { module ->
            module.allFilePaths
                .asSequence()
                .map { filePath -> Path.of(filePath).toAbsolutePath().normalize() }
                .mapNotNull(sourceFilePolicy::sourcePath)
                .forEach { path ->
                    ownersByPath
                        .getOrPut(path, ::linkedSetOf)
                        .add(module.identity)
                }
        }
        return ownersByPath.mapValues { (_, owners) -> owners.toSortedSet() }
    }

    fun legacySourceSetLabelForFile(path: WorkspaceSourcePath): GradleSourceSetName? {
        val normalizedPath = path.relative.value
        return when {
            normalizedPath.startsWith("src/main/") || "/src/main/" in normalizedPath ->
                GradleSourceSetName.parse("main")
            normalizedPath.startsWith("src/testFixtures/") || "/src/testFixtures/" in normalizedPath ->
                GradleSourceSetName.parse("testFixtures")
            normalizedPath.startsWith("src/test/") || "/src/test/" in normalizedPath ->
                GradleSourceSetName.parse("test")
            else -> runIdeaReadAction {
                val virtualFile = LocalFileSystem.getInstance()
                    .findFileByNioFile(path.absolute.value.toJavaPath())
                    ?: return@runIdeaReadAction null
                ProjectFileIndex.getInstance(project)
                    .getSourceRootForFile(virtualFile)
                    ?.name
                    ?.let(GradleSourceSetName::parse)
            }
        }
    }

    private fun indexedModuleNameForModule(module: Module): String {
        val rootManager = ModuleRootManager.getInstance(module)
        return rootManager.sourceRoots
            .asSequence()
            .mapNotNull { root -> WorkspaceRelativePath.resolve(workspaceRoot, Path.of(root.path)) }
            .mapNotNull(::legacyGradleProjectPathForWorkspacePath)
            .sorted()
            .firstOrNull()
            ?.value
            ?: module.name
    }

    private fun SourceIndexModuleIdentity.toLegacyModuleName(): String =
        sourceSet?.let { "${name.value}[${it.value}]" } ?: name.value
}
