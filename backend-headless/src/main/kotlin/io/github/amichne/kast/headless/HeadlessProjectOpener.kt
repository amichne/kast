package io.github.amichne.kast.headless

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Files
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

enum class HeadlessWorkspaceKind {
    GRADLE,
    PLAIN,
    ;

    companion object {
        fun detect(workspaceRoot: Path): HeadlessWorkspaceKind =
            if (GRADLE_MARKERS.any { marker -> Files.isRegularFile(workspaceRoot.resolve(marker)) }) {
                GRADLE
            } else {
                PLAIN
            }

        private val GRADLE_MARKERS = listOf(
            "settings.gradle.kts",
            "settings.gradle",
            "build.gradle.kts",
            "build.gradle",
        )
    }
}

class HeadlessGradleProjectBootstrap(
    private val waitForProjectModel: (Project) -> Unit = { project ->
        HeadlessGradleProjectImportBridge.awaitSmartMode(project)
    },
    private val moduleNames: (Project) -> List<String> = { project ->
        ModuleManager.getInstance(project).modules.map { module -> module.name }.sorted()
    },
    private val canLinkGradleProject: (String, Project) -> Boolean = { externalProjectPath, project ->
        HeadlessGradleProjectImportBridge.canLinkAndRefreshGradleProject(externalProjectPath, project)
    },
    private val linkAndImportGradleProject: (Project, String) -> Unit = { project, externalProjectPath ->
        HeadlessGradleProjectImportBridge.linkAndImportGradleProject(project, externalProjectPath)
    },
) {
    fun bootstrap(
        project: Project,
        workspaceRoot: Path,
        workspaceKind: HeadlessWorkspaceKind,
    ): HeadlessProjectModelBootstrapResult {
        if (workspaceKind != HeadlessWorkspaceKind.GRADLE) {
            return HeadlessProjectModelBootstrapResult.Skipped("not a Gradle project")
        }

        val initialModuleNames = moduleNames(project)
        if (initialModuleNames.isNotEmpty()) {
            return HeadlessProjectModelBootstrapResult.Ready(moduleNames = initialModuleNames, linkedGradleProject = false)
        }

        val externalProjectPath = workspaceRoot.toAbsolutePath().normalize().toString()
        if (!canLinkGradleProject(externalProjectPath, project)) {
            throw HeadlessGradleModelUnavailableException(
                "Kast opened a Gradle checkout at $externalProjectPath, but IDEA cannot link it as a Gradle project. " +
                    "Kast does not require checked-in .idea/gradle.xml; verify the checkout can be synced by Gradle " +
                    "and that the packaged headless IDEA home includes the Gradle plugins.",
            )
        }

        linkAndImportGradleProject(project, externalProjectPath)
        waitForProjectModel(project)
        val importedModuleNames = moduleNames(project)
        if (importedModuleNames.isEmpty()) {
            throw HeadlessGradleModelUnavailableException(
                "Kast linked the Gradle checkout at $externalProjectPath, but IDEA still reported no source modules. " +
                    "The headless backend needs the imported Gradle model and must not run against an empty IDEA project model.",
            )
        }
        return HeadlessProjectModelBootstrapResult.Ready(moduleNames = importedModuleNames, linkedGradleProject = true)
    }
}

sealed class HeadlessProjectModelBootstrapResult {
    data class Skipped(val reason: String) : HeadlessProjectModelBootstrapResult()
    data class Ready(
        val moduleNames: List<String>,
        val linkedGradleProject: Boolean,
    ) : HeadlessProjectModelBootstrapResult()
}

class HeadlessGradleModelUnavailableException(message: String) : IllegalStateException(message)
