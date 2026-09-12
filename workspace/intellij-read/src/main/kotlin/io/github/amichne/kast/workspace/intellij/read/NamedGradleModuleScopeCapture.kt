package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.SourceFolder
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import java.nio.file.Path
import org.jetbrains.plugins.gradle.model.ExternalSourceSet

/** One selected Gradle-build authority and its bounded module capture. */
internal class NamedGradleModuleScopeCapture(
    private val index: GradleBuildProjectIndex,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: ReadLimits = ReadLimits.Default,
) {
    fun capture(
        modules: List<Module>,
        sourceSetsFor: (Module) -> Map<String, ExternalSourceSet>,
        sourceFolders: (Module) -> List<SourceFolder> = { module ->
            ModuleRootManager.getInstance(module).contentEntries.flatMap { it.sourceFolders.toList() }
        },
    ): Refinement<NamedGradleSourceScope, NamedGradleSourceScopeFailure> {
        val entries = ArrayList<WorkspaceSourceRootBoundary>()
        val ideRoots = ArrayList<IdeCodeSourceRoot>()
        val excludedRoots = linkedSetOf<Path>()
        for (module in modules) {
            ProgressManager.checkCanceled()
            val admitted =
                when (val admission = AdmittedSelectedBuildModule.admit(module, index, limits)) {
                    is Refinement.Rejected -> return rejected(admission.failure)
                    is Refinement.Refined ->
                        when (val value = admission.value) {
                            SelectedGradleModuleAdmission.ForeignBuild -> {
                                observation.count(IntellijReadCounter.FOREIGN_GRADLE_MODULES)
                                continue
                            }
                            is SelectedGradleModuleAdmission.SelectedBuild -> value.module
                        }
                }
            observation.count(IntellijReadCounter.SELECTED_GRADLE_MODULES)
            val captured =
                when (
                    val result =
                        LiveSelectedGradleModuleRoots(admitted, observation, limits)
                            .capture(sourceSetsFor, sourceFolders)
                ) {
                    is Refinement.Rejected -> return result
                    is Refinement.Refined -> result.value
                }
            entries += captured.entries
            ideRoots += captured.ideRoots
            excludedRoots += captured.excludedRoots
        }
        return NamedGradleSourceScope.admit(
            index.root,
            NamedGradleModelObservation.Captured(entries, excludedRoots),
            ideRoots,
        )
    }

    private fun rejected(failure: NamedGradleSourceScopeFailure) = Refinement.Rejected(failure)
}
