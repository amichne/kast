package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.*
import kotlinx.coroutines.CancellationException
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
                val index =
                    when (val captured = GradleBuildProjectIndex.capture(root, imported, observation, limits)) {
                        is Refinement.Rejected -> return@readAction captured
                        is Refinement.Refined -> captured.value
                    }
                stage = NamedGradleCaptureStage.MODULES
                val modules = ModuleManager.getInstance(project).modules
                observation.count(IntellijReadCounter.IDEA_MODULES, amount = modules.size)
                if (modules.size > limits[ReadLimitParameter.MODEL_MODULES].value) {
                    observation.terminated(IntellijReadTermination.MODULE_ADMISSION_LIMIT)
                    return@readAction rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
                }
                NamedGradleModuleScopeCapture(index, observation, limits)
                    .capture(
                        modules.toList(),
                        { module -> cache.findExternalProject(imported, module) },
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
