package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes
import java.nio.file.Files
import java.nio.file.Path

internal class IdeaRelationshipCoverageAuthority(
    private val project: Project,
    private val workspaceIdentity: IdeaWorkspaceIdentity,
    private val indexSemanticAdmissionStatus: () -> IdeaIndexSemanticAdmission.Status,
    private val workspaceModelReader: () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel = {
        IdeaGradleProjectLoadBridge.readWorkspaceModel(project)
    },
) : RelationshipCoverageAuthority {
    override fun assess(
        completion: RelationshipCoverageAuthority.FamilyCompletion,
    ): RelationshipSearchCoverage = ApplicationManager.getApplication().runReadAction<RelationshipSearchCoverage> {
        val limitations = linkedSetOf<RelationshipSearchLimitation>()
        if (project.isDisposed) {
            return@runReadAction RelationshipSearchCoverage.limited(
                RelationshipSearchLimitation.BACKEND_UNAVAILABLE,
                RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
            )
        }
        if (DumbService.isDumb(project)) {
            limitations += RelationshipSearchLimitation.INDEX_NOT_READY
        }
        when (indexSemanticAdmissionStatus()) {
            IdeaIndexSemanticAdmission.Status.Ready -> Unit
            is IdeaIndexSemanticAdmission.Status.Pending ->
                limitations += RelationshipSearchLimitation.INDEX_NOT_READY
            is IdeaIndexSemanticAdmission.Status.Failed ->
                limitations += RelationshipSearchLimitation.BACKEND_INCOMPLETE
        }

        val workspace = workspaceIdentity.workspaceIdentity
        val modules = ModuleManager.getInstance(project).modules
            .filterNot { module -> module.isDisposed }
        val codeSourceRootsByModule = modules.associateWith { module ->
            ModuleRootManager.getInstance(module).getSourceRoots(JavaModuleSourceRootTypes.SOURCES)
        }
        val sourceModules = modules.filter { module -> codeSourceRootsByModule.getValue(module).isNotEmpty() }
        val gradleModel = workspaceModelReader()
        val linkedRoots = gradleModel.linkedBuildRoots()
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .toSet()
        val importedModuleIdentities = gradleModel.importedModuleIdentities().toSet()
        val loadedModules = gradleModel.loadedModules()
        val loadedModuleIdentities = loadedModules.mapTo(linkedSetOf()) { loaded -> loaded.identity() }

        if (
            sourceModules.isEmpty() ||
            linkedRoots.isEmpty() ||
            !gradleModel.importedModelComplete() ||
            importedModuleIdentities.isEmpty() ||
            loadedModuleIdentities != importedModuleIdentities
        ) {
            limitations += RelationshipSearchLimitation.PROJECT_SCOPE_INCOMPLETE
        }
        if (linkedRoots.any { root -> !workspace.contains(root) }) {
            limitations += RelationshipSearchLimitation.SOURCE_SET_EXCLUDED
        }
        if (
            importedModuleIdentities.any { identity ->
                val externalProjectPath = identity.externalProjectPath().toAbsolutePath().normalize()
                linkedRoots.none { root ->
                    externalProjectPath == root || externalProjectPath.startsWith(root)
                }
            } ||
            linkedRoots.any { root ->
                importedModuleIdentities.none { identity ->
                    val externalProjectPath = identity.externalProjectPath().toAbsolutePath().normalize()
                    externalProjectPath == root || externalProjectPath.startsWith(root)
                }
            }
        ) {
            limitations += RelationshipSearchLimitation.PROJECT_SCOPE_INCOMPLETE
        }

        val associatedModuleNames = loadedModules.mapTo(linkedSetOf()) { loaded ->
            loaded.ideaModuleName()
        }
        if (sourceModules.any { module -> module.name !in associatedModuleNames }) {
            limitations += RelationshipSearchLimitation.PROJECT_SCOPE_INCOMPLETE
        }

        val modelSourceRoots = gradleModel.importedSourceRoots()
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .filter(Files::isDirectory)
            .toSet()
        if (modelSourceRoots.isEmpty()) {
            limitations += RelationshipSearchLimitation.SOURCE_SET_SCOPE_INCOMPLETE
        }
        if (modelSourceRoots.any { root -> !workspace.contains(root) }) {
            limitations += RelationshipSearchLimitation.SOURCE_SET_EXCLUDED
        }

        val ideaSourceRoots = sourceModules
            .flatMap { module -> codeSourceRootsByModule.getValue(module) }
            .map { root -> Path.of(root.path).toAbsolutePath().normalize() }
            .toSet()
        if (ideaSourceRoots.any { root -> !workspace.contains(root) }) {
            limitations += RelationshipSearchLimitation.SOURCE_SET_EXCLUDED
        }
        if (ideaSourceRoots != modelSourceRoots) {
            limitations += RelationshipSearchLimitation.SOURCE_SET_SCOPE_INCOMPLETE
        }

        if (limitations.isNotEmpty()) {
            return@runReadAction RelationshipSearchCoverage.Limited.from(
                limitations + RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
            )
        }
        when (completion) {
            RelationshipCoverageAuthority.FamilyCompletion.COMPLETE ->
                RelationshipSearchCoverage.complete()
            RelationshipCoverageAuthority.FamilyCompletion.RESUMABLE ->
                RelationshipSearchCoverage.resumable()
            RelationshipCoverageAuthority.FamilyCompletion.INCOMPLETE ->
                RelationshipSearchCoverage.limited(
                    RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
                )
        }
    }
}
