package io.github.amichne.kast.indexer.project

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectBootstrap
import java.nio.file.Path

class ProjectOpener(
    private val gradleProjectBootstrap: GradleProjectBootstrap = GradleProjectBootstrap(),
    private val openProjectIdentity: (Path, OpenProjectTask) -> Project = { projectIdentity, task ->
        ProjectManagerEx.getInstanceEx()
            .openProject(projectIdentity, task)
            ?: error("IDEA could not open Kast project identity: $projectIdentity")
    },
) {
    fun openProject(
        workspaceRoot: Path,
        layout: IndexerProjectLayout,
        onProjectIdentityOpened: (Project) -> Unit = {},
    ): Project {
        val sourceRoot = workspaceRoot.toAbsolutePath().normalize()
        require(sourceRoot == layout.workspaceRoot) {
            "Indexer project layout does not belong to the exact workspace root: $sourceRoot"
        }
        layout.prepare()
        val workspaceKind = WorkspaceKind.detect(sourceRoot)
        val project = openProjectIdentity(layout.projectIdentityDirectory, openProjectTask(layout))
        onProjectIdentityOpened(project)
        gradleProjectBootstrap.bootstrap(
            project,
            sourceRoot,
            workspaceKind,
            layout.gradleProjectCacheDirectory,
        )

        println("Project opened: ${project.name}")
        return project
    }

    companion object {
        fun openProjectTask(): OpenProjectTask = baseOpenProjectTask()

        fun openProjectTask(layout: IndexerProjectLayout): OpenProjectTask = baseOpenProjectTask().copy(
            projectRootDir = layout.projectIdentityDirectory,
            preventIprLookup = true,
        )

        private fun baseOpenProjectTask(): OpenProjectTask = OpenProjectTask.build().copy(
            isRefreshVfsNeeded = false,
            runConfigurators = false,
            runConversionBeforeOpen = false,
            preloadServices = false,
        )
    }
}
