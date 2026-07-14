package io.github.amichne.kast.idea

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteException
import io.github.amichne.kast.api.protocol.WorkspaceProjectModelIncompleteReason
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal class IdeaProjectModelWorkspaceFileInventory(
    private val workspaceIdentity: WorkspaceIdentity,
    private val projectModelAccess: IdeaWorkspaceFileProjectModelAccess,
) : IdeaWorkspaceFileInventory {
    constructor(
        project: Project,
        workspaceIdentity: IdeaWorkspaceIdentity,
    ) : this(
        workspaceIdentity = workspaceIdentity.workspaceIdentity,
        projectModelAccess = IdeaProjectModelAccess(project),
    )

    override fun snapshot(kindDomain: WorkspaceFileKindDomain): IdeaWorkspaceFileInventorySnapshot {
        if (projectModelAccess.isIndexing) {
            throw WorkspaceProjectModelIncompleteException(
                WorkspaceProjectModelIncompleteReason.RUNTIME_INDEXING,
            )
        }
        val projectModel = try {
            projectModelAccess.read()
        } catch (failure: ProcessCanceledException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: WorkspaceProjectModelIncompleteException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw WorkspaceProjectModelIncompleteException(
                reason = WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE,
                message = "Gradle project model is unavailable: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
        return readSnapshot(kindDomain, projectModel)
    }

    private fun readSnapshot(
        kindDomain: WorkspaceFileKindDomain,
        projectModel: IdeaWorkspaceFileProjectModel,
    ): IdeaWorkspaceFileInventorySnapshot {
        val evidenceByModule = projectModel.modules
            .sortedBy { module -> module.identity }
            .associate { module ->
                module.identity to ModuleEvidence.from(module, ::canonicalContainedPath)
            }
        val linkedRoots = projectModel.linkedBuildRoots
            .mapNotNull(::canonicalContainedPath)
            .distinct()
            .sorted()
        if (workspaceIdentity.gradleRoot != null && linkedRoots.isEmpty()) {
            throw WorkspaceProjectModelIncompleteException(
                reason = WorkspaceProjectModelIncompleteReason.PROJECT_MODEL_UNAVAILABLE,
                message = "Workspace has Gradle settings but IDEA has no linked Gradle project model",
            )
        }
        val associations = projectModel.moduleAssociations
            .mapNotNull { association ->
                canonicalContainedPath(association.linkedBuildRoot)
                    ?.let { linkedRoot -> association.copy(linkedBuildRoot = Path.of(linkedRoot)) }
            }
        val rootModuleIdentitiesByLinkedRoot = linkedRoots.associateWith { linkedRoot ->
            associations
                .filter { association ->
                    association.rootModule && association.linkedBuildRoot.toString() == linkedRoot
                }.map(IdeaWorkspaceFileProjectModel.GradleModuleAssociation::moduleIdentity)
                .filter(evidenceByModule::containsKey)
                .distinct()
                .sorted()
        }
        rootModuleIdentitiesByLinkedRoot.forEach { (linkedRoot, rootModuleIdentities) ->
            if (rootModuleIdentities.isEmpty()) {
                throw WorkspaceProjectModelIncompleteException(
                    reason = WorkspaceProjectModelIncompleteReason.LINKED_ROOT_UNASSOCIATED,
                    message = "Linked Gradle root has no root-module association: $linkedRoot",
                )
            }
        }

        projectModel.modules.forEach { module ->
            val evidence = evidenceByModule[module.identity] ?: return@forEach
            module.ownedFilePaths
                .mapNotNull(::canonicalContainedPath)
                .forEach(evidence::add)
        }
        val rootGradleScriptPaths = projectModel.rootGradleScriptPaths
            .mapNotNull(::canonicalContainedPath)
            .toSet()
        rootModuleIdentitiesByLinkedRoot.forEach { (linkedRoot, rootModuleIdentities) ->
            ROOT_GRADLE_SCRIPTS
                .map { scriptName -> Path.of(linkedRoot).resolve(scriptName).normalize().toString() }
                .filter(rootGradleScriptPaths::contains)
                .forEach { scriptPath ->
                    rootModuleIdentities.forEach { moduleIdentity ->
                        evidenceByModule.getValue(moduleIdentity).add(scriptPath)
                    }
                }
        }

        return IdeaWorkspaceFileInventorySnapshot.create(
            kindDomain = kindDomain,
            modules = evidenceByModule.values.map(ModuleEvidence::snapshot),
        )
    }

    private fun canonicalContainedPath(path: Path): String? {
        val normalized = path.toAbsolutePath().normalize()
        return workspaceIdentity
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

        fun add(path: String) {
            when {
                path.endsWith(".kts") -> scriptFilePaths += path
                path.endsWith(".kt") -> sourceFilePaths += path
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
                module: IdeaWorkspaceFileProjectModel.Module,
                canonicalContainedPath: (Path) -> String?,
            ): ModuleEvidence = ModuleEvidence(
                identity = module.identity,
                sourceRoots = module.sourceRoots.mapNotNull(canonicalContainedPath),
                contentRoots = module.contentRoots.mapNotNull(canonicalContainedPath),
                dependencyModuleNames = module.dependencyModuleNames.map(IdeaWorkspaceModuleIdentity::value),
            )
        }
    }

    private class IdeaProjectModelAccess(
        private val project: Project,
    ) : IdeaWorkspaceFileProjectModelAccess {
        override val isIndexing: Boolean
            get() = DumbService.isDumb(project)

        override fun read(): IdeaWorkspaceFileProjectModel = runIdeaReadAction {
            val gradleModel = IdeaGradleProjectLoadBridge.readWorkspaceModel(project)
            val kotlinFileType = FileTypeManager.getInstance().findFileTypeByName("Kotlin")
                ?: throw IllegalStateException("Kotlin file type is unavailable from the IDEA project model")
            val modules = ModuleManager.getInstance(project).modules
                .sortedBy(Module::getName)
                .map { module -> moduleModel(module, kotlinFileType) }
            val rootGradleScriptPaths = gradleModel.linkedBuildRoots()
                .flatMap { linkedRoot ->
                    ROOT_GRADLE_SCRIPTS.mapNotNull { scriptName ->
                        LocalFileSystem.getInstance().findFileByNioFile(linkedRoot.resolve(scriptName))
                            ?.takeIf(VirtualFile::isValid)
                            ?.toNioPath()
                    }
                }.toSet()
            IdeaWorkspaceFileProjectModel(
                modules = modules,
                linkedBuildRoots = gradleModel.linkedBuildRoots(),
                moduleAssociations = gradleModel.moduleAssociations().map { association ->
                    IdeaWorkspaceFileProjectModel.GradleModuleAssociation(
                        moduleIdentity = IdeaWorkspaceModuleIdentity.of(association.ideaModuleName()),
                        linkedBuildRoot = association.linkedBuildRoot(),
                        rootModule = association.rootModule(),
                    )
                },
                rootGradleScriptPaths = rootGradleScriptPaths,
            )
        }

        private fun moduleModel(
            module: Module,
            kotlinFileType: FileType,
        ): IdeaWorkspaceFileProjectModel.Module {
            val roots = ModuleRootManager.getInstance(module)
            return IdeaWorkspaceFileProjectModel.Module(
                identity = IdeaWorkspaceModuleIdentity.of(module.name),
                sourceRoots = roots.sourceRoots.map(VirtualFile::toNioPath),
                contentRoots = roots.contentRoots.map(VirtualFile::toNioPath),
                dependencyModuleNames = roots.dependencies.map { dependency ->
                    IdeaWorkspaceModuleIdentity.of(dependency.name)
                },
                ownedFilePaths = kotlinCandidates(module, kotlinFileType),
            )
        }

        private fun kotlinCandidates(
            module: Module,
            kotlinFileType: FileType,
        ): List<Path> = FileTypeIndex.getFiles(kotlinFileType, GlobalSearchScope.moduleScope(module))
            .asSequence()
            .filter(VirtualFile::isValid)
            .filterNot(VirtualFile::isDirectory)
            .filter { file -> file.path.endsWith(".kt") || file.path.endsWith(".kts") }
            .distinctBy(VirtualFile::getPath)
            .sortedBy(VirtualFile::getPath)
            .map(VirtualFile::toNioPath)
            .toList()
    }

    private companion object {
        val ROOT_GRADLE_SCRIPTS = listOf("settings.gradle.kts", "build.gradle.kts")
    }
}
