package io.github.amichne.kast.headless

import com.intellij.openapi.project.Project
import java.nio.file.Path

class HeadlessGradleProjectBootstrap(
    private val waitForProjectModel: (Project) -> Unit = { project ->
        HeadlessGradleProjectImportBridge.awaitGradleModelSettlement(project)
    },
    private val inspectProjectModel: (Project) -> HeadlessGradleModelReadiness = { project ->
        HeadlessGradleProjectImportBridge.inspectProjectModel(project)
    },
    private val canLinkGradleProject: (String, Project) -> Boolean = { externalProjectPath, project ->
        HeadlessGradleProjectImportBridge.canLinkAndRefreshGradleProject(externalProjectPath, project)
    },
    private val linkAndImportGradleProject: (Project, String) -> Unit = { project, externalProjectPath ->
        HeadlessGradleProjectImportBridge.linkAndImportGradleProject(project, externalProjectPath)
    },
    private val waitBeforeReadinessRetry: () -> Unit = {
        try {
            Thread.sleep(MODEL_READINESS_RETRY_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HeadlessGradleModelUnavailableException("Interrupted while waiting for a compiler-ready Gradle model")
        }
    },
    private val maxReadinessChecks: Int = DEFAULT_MODEL_READINESS_CHECKS,
) {
    init {
        require(maxReadinessChecks > 0) { "maxReadinessChecks must be positive" }
    }

    fun bootstrap(
        project: Project,
        workspaceRoot: Path,
        workspaceKind: HeadlessWorkspaceKind,
    ): HeadlessProjectModelBootstrapResult {
        if (workspaceKind != HeadlessWorkspaceKind.GRADLE) {
            return HeadlessProjectModelBootstrapResult.Skipped("not a Gradle project")
        }

        val modelBeforeSync = inspectProjectModel(project)
        val externalProjectPath = workspaceRoot.toAbsolutePath().normalize().toString()
        if (!canLinkGradleProject(externalProjectPath, project)) {
            throw HeadlessGradleModelUnavailableException(
                "Kast opened a Gradle checkout at $externalProjectPath, but IDEA cannot synchronize it as a Gradle project. " +
                    "IDEA reported ${modelBeforeSync.moduleNames.size} modules before synchronization. " +
                    "Kast does not require checked-in .idea/gradle.xml; verify the checkout can be synced by Gradle " +
                    "and that the packaged headless IDEA home includes the Gradle plugins.",
            )
        }

        waitForProjectModel(project)
        var latestModel = inspectProjectModel(project)
        if (latestModel.compilerReady) {
            return HeadlessProjectModelBootstrapResult.Ready(
                moduleNames = latestModel.moduleNames,
                linkedGradleProject = true,
            )
        }

        linkAndImportGradleProject(project, externalProjectPath)
        repeat(maxReadinessChecks) { attempt ->
            waitForProjectModel(project)
            latestModel = inspectProjectModel(project)
            if (latestModel.compilerReady) {
                return HeadlessProjectModelBootstrapResult.Ready(
                    moduleNames = latestModel.moduleNames,
                    linkedGradleProject = true,
                )
            }
            if (attempt + 1 < maxReadinessChecks) {
                waitBeforeReadinessRetry()
            }
        }
        throw HeadlessGradleModelUnavailableException(
                "Kast synchronized the Gradle checkout at $externalProjectPath, but its compiler model did not become usable. " +
                "IDEA reported ${latestModel.moduleNames.size} modules, " +
                "${latestModel.kotlinSourceModuleNames.size} Kotlin source modules, and " +
                "${latestModel.compilerReadyKotlinModuleNames.size} compiler-ready Kotlin modules. " +
                "Unready Kotlin modules: ${latestModel.unavailableKotlinModuleNames.joinToString().ifEmpty { "<none discovered>" }}. " +
                "The headless backend must not advertise READY until Gradle is idle and Kotlin, JDK, SDK, library, and order-entry resolution are coherent.",
        )
    }

    private companion object {
        const val MODEL_READINESS_RETRY_MILLIS: Long = 250L
        const val DEFAULT_MODEL_READINESS_CHECKS: Int = 240
    }
}
