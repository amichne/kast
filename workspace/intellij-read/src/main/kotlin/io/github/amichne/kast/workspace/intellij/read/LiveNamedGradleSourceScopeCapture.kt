package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.*
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.plugins.gradle.model.ExternalProject
import org.jetbrains.plugins.gradle.service.project.data.ExternalProjectDataCache

/** Cached imported Gradle facts only. No resolver registration, sync, VFS refresh or project lookup. */
internal object LiveNamedGradleSourceScopeCapture {
    suspend fun capture(
        project: Project,
        root: CanonicalWorkspaceRoot,
        observation: IntellijReadObservation = IntellijReadObservation.None,
        limits: ReadLimits = ReadLimits.Default,
    ): Refinement<NamedGradleSourceScope, NamedGradleSourceScopeFailure> {
        var stage = NamedGradleCaptureStage.PROJECT
        return try {
            readAction {
                if (project.isDisposed || !project.isOpen || !project.isInitialized) {
                    return@readAction rejected(NamedGradleSourceScopeFailure.PROJECT_UNAVAILABLE)
                }
                if (DumbService.isDumb(project)) return@readAction rejected(NamedGradleSourceScopeFailure.INDEXING)
                stage = NamedGradleCaptureStage.CACHE
                val cache = ExternalProjectDataCache.getInstance(project)
                val imported =
                    cache.getRootExternalProject(root.value)
                        ?: return@readAction rejected(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE)
                val projects = ArrayList<ExternalProject>()
                val pending = ArrayDeque<ExternalProject>().apply { add(imported) }
                while (pending.isNotEmpty()) {
                    ProgressManager.checkCanceled()
                    val current = pending.removeFirst()
                    projects += current
                    observation.count(IntellijReadCounter.IMPORTED_PROJECTS)
                    if (
                        projects.size + pending.size + current.childProjects.size >
                            limits[ReadLimitParameter.MODEL_MODULES].value
                    ) {
                        observation.terminated(IntellijReadTermination.MODULE_ADMISSION_LIMIT)
                        return@readAction rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
                    }
                    pending.addAll(current.childProjects.values)
                }
                stage = NamedGradleCaptureStage.MODULES
                val modules = ModuleManager.getInstance(project).modules
                observation.count(IntellijReadCounter.IDEA_MODULES, amount = modules.size)
                if (modules.size > limits[ReadLimitParameter.MODEL_MODULES].value) {
                    observation.terminated(IntellijReadTermination.MODULE_ADMISSION_LIMIT)
                    return@readAction rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
                }
                val entries = ArrayList<WorkspaceSourceRootBoundary>()
                val ideRoots = ArrayList<IdeCodeSourceRoot>()
                val excludedRoots = linkedSetOf<Path>()
                for (module in modules) {
                    ProgressManager.checkCanceled()
                    if (module.isDisposed) return@readAction rejected(NamedGradleSourceScopeFailure.PROJECT_UNAVAILABLE)
                    val folders =
                        ModuleRootManager.getInstance(module).contentEntries.flatMap { it.sourceFolders.toList() }
                    observation.count(IntellijReadCounter.SOURCE_ROOTS, amount = folders.size)
                    if (folders.size > limits[ReadLimitParameter.MODEL_SOURCE_ROOTS_PER_MODULE].value) {
                        observation.terminated(IntellijReadTermination.SOURCE_ROOT_ADMISSION_LIMIT)
                        return@readAction rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
                    }
                    for (folder in folders) {
                        when (folder.rootType) {
                            is JavaSourceRootType -> Unit
                            is JavaResourceRootType -> {
                                val file =
                                    folder.file
                                        ?: return@readAction rejected(NamedGradleSourceScopeFailure.IDE_ROOT_UNMAPPED)
                                excludedRoots.add(file.toNioPath())
                            }
                            else -> return@readAction rejected(NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT)
                        }
                    }
                    val codeFolders = folders.filter { it.rootType is JavaSourceRootType }
                    if (codeFolders.isEmpty()) continue
                    if (!ExternalSystemApiUtil.isExternalSystemAwareModule("GRADLE", module)) {
                        return@readAction rejected(NamedGradleSourceScopeFailure.OWNER_UNAVAILABLE)
                    }
                    val externalPath =
                        ExternalSystemApiUtil.getExternalProjectPath(module)
                            ?: return@readAction rejected(NamedGradleSourceScopeFailure.OWNER_UNAVAILABLE)
                    val buildPath =
                        ExternalSystemApiUtil.getExternalRootProjectPath(module)
                            ?: return@readAction rejected(NamedGradleSourceScopeFailure.OWNER_UNAVAILABLE)
                    val owner =
                        projects.singleOrNull { it.projectDir.toPath() == Path.of(externalPath) }
                            ?: return@readAction rejected(NamedGradleSourceScopeFailure.OWNER_UNAVAILABLE)
                    for (folder in codeFolders) {
                        val file =
                            folder.file ?: return@readAction rejected(NamedGradleSourceScopeFailure.IDE_ROOT_UNMAPPED)
                        val properties =
                            folder.jpsElement.properties as? JavaSourceRootProperties
                                ?: return@readAction rejected(NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT)
                        ideRoots +=
                            IdeCodeSourceRoot(
                                file.toNioPath(),
                                if (folder.rootType == JavaSourceRootType.TEST_SOURCE) WorkspaceSourceRootKind.TEST
                                else WorkspaceSourceRootKind.PRODUCTION,
                                if (properties.isForGeneratedSources) WorkspaceSourceRootProvenance.GENERATED
                                else WorkspaceSourceRootProvenance.AUTHORED,
                            )
                    }
                    stage = NamedGradleCaptureStage.SOURCE_SETS
                    val sourceSets = cache.findExternalProject(imported, module)
                    if (sourceSets.isEmpty())
                        return@readAction rejected(NamedGradleSourceScopeFailure.MODEL_UNAVAILABLE)
                    if (sourceSets.size > limits[ReadLimitParameter.MODEL_SOURCE_ROOTS_PER_MODULE].value) {
                        return@readAction rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
                    }
                    var moduleRoots = 0
                    for ((name, sourceSet) in sourceSets) {
                        ProgressManager.checkCanceled()
                        if (name != sourceSet.name)
                            return@readAction rejected(NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT)
                        for ((type, directories) in sourceSet.sources) {
                            for (path in directories.srcDirs) {
                                if (++moduleRoots > limits[ReadLimitParameter.MODEL_SOURCE_ROOTS_PER_MODULE].value) {
                                    return@readAction rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
                                }
                                if (type.isResource || type.isExcluded) excludedRoots.add(path.toPath())
                                if (type.isResource) continue
                                entries +=
                                    WorkspaceSourceRootBoundary(
                                        module.name,
                                        Path.of(buildPath),
                                        owner.path,
                                        sourceSet.name,
                                        path.toPath(),
                                        if (type.isTest) WorkspaceSourceRootKind.TEST
                                        else WorkspaceSourceRootKind.PRODUCTION,
                                        if (type.isGenerated) WorkspaceSourceRootProvenance.GENERATED
                                        else WorkspaceSourceRootProvenance.AUTHORED,
                                    )
                            }
                        }
                    }
                }
                stage = NamedGradleCaptureStage.OWNERSHIP
                NamedGradleSourceScope.admit(
                    root,
                    NamedGradleModelObservation.Captured(entries, excludedRoots),
                    ideRoots,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            observation.unexpected(
                IntellijReadUnexpectedFailure.capture(IntellijReadStage.MODEL_CAPTURE, failure, limits)
            )
            rejected(NamedGradleSourceScopeFailure.ObservationFailed(stage))
        }
    }

    private fun rejected(failure: NamedGradleSourceScopeFailure) = Refinement.Rejected(failure)
}
