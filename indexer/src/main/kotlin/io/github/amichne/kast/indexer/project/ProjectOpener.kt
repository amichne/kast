package io.github.amichne.kast.indexer.project

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectBootstrap
import io.github.amichne.kast.indexer.gradle.bootstrap.InitialProjectModelAuthority
import io.github.amichne.kast.indexer.project.indexing.KastWorkspaceDirectoryIndexExclusionAdmission
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
            .openProject(projectPath, openProjectTask(projectPath))
            ?: error("IDEA could not open project: $projectPath")
        val bootstrapped = gradleProjectBootstrap.bootstrapProject(project, projectPath, workspaceKind)

        println("Project opened: ${project.name}")
        return OpenedProject(project, bootstrapped.initialProjectModelAuthority)
    }

    companion object {
        /**
         * Proof transition: `Path -> OpenProjectTask`.
         *
         * Establishes that the exact absolute, normalized Kast workspace gains
         * its private directory-index exclusion policy in `beforeInit`, before
         * IntelliJ starts project indexing. The admission never applies to an
         * unrelated project or application-wide test fixture.
         */
        internal fun openProjectTask(workspaceRoot: Path): OpenProjectTask {
            val admission = KastWorkspaceDirectoryIndexExclusionAdmission.fromWorkspaceRoot(workspaceRoot)
            return openProjectTask().copy(beforeInit = admission::installForProjectLifetime)
        }

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
