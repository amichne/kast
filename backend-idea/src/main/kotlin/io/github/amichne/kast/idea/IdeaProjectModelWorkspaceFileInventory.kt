package io.github.amichne.kast.idea

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteException
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteReason
import java.nio.file.Path

internal class IdeaProjectModelWorkspaceFileInventory(
    private val project: Project,
    private val workspaceIdentity: IdeaWorkspaceIdentity,
) : IdeaWorkspaceFileInventory {
    override fun snapshot(kindDomain: WorkspaceFileKindDomain): IdeaWorkspaceFileInventorySnapshot {
        if (DumbService.isDumb(project)) {
            throw WorkspaceProjectModelIncompleteException(
                WorkspaceProjectModelIncompleteReason.RUNTIME_INDEXING,
            )
        }
        return runIdeaReadAction {
            readSnapshot(kindDomain)
        }
    }

    private fun readSnapshot(kindDomain: WorkspaceFileKindDomain): IdeaWorkspaceFileInventorySnapshot {
        val gradleModel = try {
            IdeaGradleProjectLoadBridge.readWorkspaceModel(project)
        } catch (failure: RuntimeException) {
            throw WorkspaceProjectModelIncompleteException(
                reason = WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE,
                message = "Gradle project model is unavailable: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
        val modules = ModuleManager.getInstance(project).modules
            .sortedBy(Module::getName)
        val evidenceByModule = modules.associate { module ->
            val identity = IdeaWorkspaceModuleIdentity.of(module.name)
            identity to ModuleEvidence.from(module, identity, ::canonicalContainedPath)
        }
        val linkedRoots = gradleModel.linkedBuildRoots()
            .mapNotNull(::canonicalContainedPath)
            .distinct()
            .sorted()
        if (workspaceIdentity.workspaceIdentity.gradleRoot != null && linkedRoots.isEmpty()) {
            throw WorkspaceProjectModelIncompleteException(
                reason = WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE,
                message = "Workspace has Gradle settings but IDEA has no linked Gradle project model",
            )
        }
        val associations = gradleModel.moduleAssociations()
            .filter { association -> canonicalContainedPath(association.linkedBuildRoot()) != null }
        if (linkedRoots.isNotEmpty() && associations.isEmpty()) {
            throw WorkspaceProjectModelIncompleteException(
                WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE,
            )
        }
        val rootModuleNamesByLinkedRoot = linkedRoots.associateWith { linkedRoot ->
            associations
                .filter { association ->
                    association.rootModule() && canonicalContainedPath(association.linkedBuildRoot()) == linkedRoot
                }.map { association -> association.ideaModuleName() }
                .filter(evidenceByModule.values.map { evidence -> evidence.identity.value }.toSet()::contains)
                .distinct()
                .sorted()
        }
        rootModuleNamesByLinkedRoot.forEach { (linkedRoot, rootModuleNames) ->
            if (rootModuleNames.isEmpty()) {
                throw WorkspaceProjectModelIncompleteException(
                    reason = WorkspaceProjectModelIncompleteReason.LINKED_ROOT_UNASSOCIATED,
                    message = "Linked Gradle root has no root-module association: $linkedRoot",
                )
            }
        }

        kotlinCandidates(modules).forEach { file ->
            evidenceByModule.values
                .filter { evidence -> evidence.contains(file.path) }
                .forEach { evidence -> evidence.add(file) }
        }
        rootModuleNamesByLinkedRoot.forEach { (linkedRoot, rootModuleNames) ->
            ROOT_GRADLE_SCRIPTS.forEach { scriptName ->
                val script = LocalFileSystem.getInstance().findFileByNioFile(Path.of(linkedRoot).resolve(scriptName))
                    ?.takeIf(VirtualFile::isValid)
                    ?: return@forEach
                rootModuleNames.forEach { moduleName ->
                    evidenceByModule[IdeaWorkspaceModuleIdentity.of(moduleName)]?.add(script)
                }
            }
        }

        return IdeaWorkspaceFileInventorySnapshot.create(
            kindDomain = kindDomain,
            modules = evidenceByModule.values.map(ModuleEvidence::snapshot),
        )
    }

    private fun kotlinCandidates(modules: List<Module>): List<VirtualFile> {
        val kotlinFileType = FileTypeManager.getInstance().findFileTypeByName("Kotlin")
            ?: throw WorkspaceProjectModelIncompleteException(
                reason = WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE,
                message = "Kotlin file type is unavailable from the IDEA project model",
            )
        val projectFiles = FileTypeIndex.getFiles(kotlinFileType, GlobalSearchScope.projectScope(project))
        val moduleFiles = modules.flatMap { module ->
            FileTypeIndex.getFiles(kotlinFileType, GlobalSearchScope.moduleScope(module))
        }
        return (projectFiles + moduleFiles)
            .asSequence()
            .filter(VirtualFile::isValid)
            .filterNot(VirtualFile::isDirectory)
            .filter { file -> file.path.endsWith(".kt") || file.path.endsWith(".kts") }
            .filter { file -> canonicalContainedPath(file.path) != null }
            .distinctBy(VirtualFile::getPath)
            .sortedBy(VirtualFile::getPath)
            .toList()
    }

    private fun canonicalContainedPath(path: String): String? =
        canonicalContainedPath(Path.of(path))

    private fun canonicalContainedPath(path: Path): String? {
        val normalized = path.toAbsolutePath().normalize()
        return workspaceIdentity.workspaceIdentity
            .relativizeIfContained(normalized)
            ?.let { normalized.toString() }
    }

    private class ModuleEvidence private constructor(
        val identity: IdeaWorkspaceModuleIdentity,
        private val sourceRoots: List<String>,
        private val contentRoots: List<String>,
        private val dependencyModuleNames: List<String>,
    ) {
        private val sourceFilePaths = linkedSetOf<String>()
        private val scriptFilePaths = linkedSetOf<String>()

        fun contains(path: String): Boolean {
            val candidate = Path.of(path).toAbsolutePath().normalize()
            return contentRoots.any { root -> candidate.startsWith(Path.of(root)) }
        }

        fun add(file: VirtualFile) {
            when {
                file.path.endsWith(".kts") -> scriptFilePaths += file.path
                file.path.endsWith(".kt") -> sourceFilePaths += file.path
            }
        }

        fun snapshot(): IdeaWorkspaceModuleSnapshot = IdeaWorkspaceModuleSnapshot.create(
            identity = identity,
            sourceRoots = sourceRoots,
            contentRoots = contentRoots,
            dependencyModuleNames = dependencyModuleNames,
            sourceFilePaths = sourceFilePaths,
            scriptFilePaths = scriptFilePaths,
        )

        companion object {
            fun from(
                module: Module,
                identity: IdeaWorkspaceModuleIdentity,
                canonicalContainedPath: (String) -> String?,
            ): ModuleEvidence {
                val roots = ModuleRootManager.getInstance(module)
                return ModuleEvidence(
                    identity = identity,
                    sourceRoots = roots.sourceRoots.mapNotNull { root -> canonicalContainedPath(root.path) },
                    contentRoots = roots.contentRoots.mapNotNull { root -> canonicalContainedPath(root.path) },
                    dependencyModuleNames = roots.dependencies.map(Module::getName),
                )
            }
        }
    }

    private companion object {
        val ROOT_GRADLE_SCRIPTS = listOf("settings.gradle.kts", "build.gradle.kts")
    }
}
