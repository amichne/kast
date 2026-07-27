package io.github.amichne.kast.headless

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

class HeadlessProjectOpener(
    private val gradleProjectBootstrap: HeadlessGradleProjectBootstrap = HeadlessGradleProjectBootstrap(),
) {
    fun openProject(workspaceRoot: Path): Project {
        val projectPath = workspaceRoot.toAbsolutePath().normalize()
        val workspaceKind = HeadlessWorkspaceKind.detect(projectPath)
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
