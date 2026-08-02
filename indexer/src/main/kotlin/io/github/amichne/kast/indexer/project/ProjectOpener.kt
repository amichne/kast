package io.github.amichne.kast.indexer.project

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectBootstrap
import java.nio.file.Path

class ProjectOpener(
    private val gradleProjectBootstrap: GradleProjectBootstrap = GradleProjectBootstrap(),
) {
    fun openProject(workspaceRoot: Path): Project {
        val projectPath = workspaceRoot.toAbsolutePath().normalize()
        val workspaceKind = WorkspaceKind.detect(projectPath)
        val project = ProjectManagerEx.getInstanceEx()
            .openProject(projectPath, openProjectTask())
            ?: error("IDEA could not open project: $projectPath")
        gradleProjectBootstrap.bootstrap(project, projectPath, workspaceKind)

        println("Project opened: ${project.name}")
        return project
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
