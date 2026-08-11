package io.github.amichne.kast.indexer.project

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectBootstrap
import io.github.amichne.kast.indexer.gradle.bootstrap.InitialProjectModelAuthority
import java.nio.file.Path

class ProjectOpener(
    private val gradleProjectBootstrap: GradleProjectBootstrap = GradleProjectBootstrap(),
) {
    /**
     * Effectful proof transition: `Path -> OpenedProject`.
     *
     * Establishes that IntelliJ opened the exact path and completed project-model
     * bootstrap. The returned aggregate retains the bootstrap-derived initial
     * model authority instead of relying on later call order.
     */
    internal fun openProject(workspaceRoot: Path): OpenedProject {
        val projectPath = workspaceRoot.toAbsolutePath().normalize()
        val workspaceKind = WorkspaceKind.detect(projectPath)
        val project = ProjectManagerEx.getInstanceEx()
            .openProject(projectPath, openProjectTask())
            ?: error("IDEA could not open project: $projectPath")
        val bootstrapped = gradleProjectBootstrap.bootstrapProject(project, projectPath, workspaceKind)

        println("Project opened: ${project.name}")
        return OpenedProject(project, bootstrapped.initialProjectModelAuthority)
    }

    companion object {
        fun openProjectTask(): OpenProjectTask = OpenProjectTask.build().copy(
            isRefreshVfsNeeded = false,
            runConfigurators = false,
            runConversionBeforeOpen = false,
            preloadServices = false,
        )
    }
}

internal data class OpenedProject(
    val project: Project,
    val initialProjectModelAuthority: InitialProjectModelAuthority,
)
