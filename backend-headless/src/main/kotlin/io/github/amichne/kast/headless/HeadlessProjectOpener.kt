package io.github.amichne.kast.headless

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

class HeadlessProjectOpener {
    fun openProject(workspaceRoot: Path): Project {
        val projectPath = workspaceRoot.toAbsolutePath().normalize()
        val project = ProjectManagerEx.getInstanceEx()
            .openProject(projectPath, OpenProjectTask.build())
            ?: error("IntelliJ could not open project: $projectPath")

        DumbService.getInstance(project).waitForSmartMode()
        println("Project opened and indexes ready: ${project.name}")
        return project
    }
}
