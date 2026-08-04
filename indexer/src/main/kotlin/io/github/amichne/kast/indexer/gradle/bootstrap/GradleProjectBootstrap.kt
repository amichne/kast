package io.github.amichne.kast.indexer.gradle.bootstrap

import com.intellij.openapi.project.Project
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelReadiness
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelSettlementEvidence
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelUnavailableException
import io.github.amichne.kast.indexer.project.ProjectModelBootstrapResult
import io.github.amichne.kast.indexer.project.WorkspaceKind
import java.nio.file.Path

class GradleProjectBootstrap(
    private val configureGradleImport: (Project) -> Unit = { project ->
        GradleProjectImportBridge.configureIndexerImport(project)
    },
    private val waitForProjectModel: (Project) -> GradleModelSettlementEvidence = { project ->
        GradleProjectImportBridge.awaitGradleModelSettlement(project)
    },
    private val inspectProjectModel: (Project) -> GradleModelReadiness = { project ->
        GradleProjectImportBridge.inspectProjectModel(project)
    },
    private val canLinkGradleProject: (String, Project) -> Boolean = { externalProjectPath, project ->
        GradleProjectImportBridge.canLinkAndRefreshGradleProject(externalProjectPath, project)
    },
    private val hasLinkedGradleProject: (String, Project) -> Boolean = { externalProjectPath, project ->
        GradleProjectImportBridge.hasLinkedGradleProject(project, externalProjectPath)
    },
    private val linkAndImportGradleProject: (Project, String) -> Unit = { project, externalProjectPath ->
        GradleProjectImportBridge.linkAndImportGradleProject(project, externalProjectPath)
    },
    private val waitBeforeReadinessRetry: () -> Unit = {
        try {
            Thread.sleep(MODEL_READINESS_RETRY_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GradleModelUnavailableException("Interrupted while waiting for a compiler-ready Gradle model")
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
        workspaceKind: WorkspaceKind,
    ): ProjectModelBootstrapResult {
        if (workspaceKind != WorkspaceKind.GRADLE) {
            return ProjectModelBootstrapResult.Skipped("not a Gradle project")
        }

        configureGradleImport(project)
        val modelBeforeSync = inspectProjectModel(project)
        val externalProjectPath = workspaceRoot.toAbsolutePath().normalize().toString()
        if (!canLinkGradleProject(externalProjectPath, project)) {
            throw GradleModelUnavailableException(
                "Kast opened a Gradle checkout at $externalProjectPath, but IDEA cannot synchronize it as a Gradle project. " +
                    "IDEA reported ${modelBeforeSync.moduleNames.size} modules before synchronization. " +
                    "Kast does not require checked-in .idea/gradle.xml; verify the checkout can be synced by Gradle " +
                    "and that the packaged indexer home includes the Gradle plugins.",
            )
        }

        waitForProjectModel(project)
        var latestModel = inspectProjectModel(project)
        if (latestModel.compilerReady && hasLinkedGradleProject(externalProjectPath, project)) {
            return ProjectModelBootstrapResult.Ready(
                moduleNames = latestModel.moduleNames,
                linkedGradleProject = true,
            )
        }

        linkAndImportGradleProject(project, externalProjectPath)
        repeat(maxReadinessChecks) { attempt ->
            waitForProjectModel(project)
            latestModel = inspectProjectModel(project)
            if (latestModel.compilerReady) {
                return ProjectModelBootstrapResult.Ready(
                    moduleNames = latestModel.moduleNames,
                    linkedGradleProject = true,
                )
            }
            if (attempt + 1 < maxReadinessChecks) {
                waitBeforeReadinessRetry()
            }
        }
        throw GradleModelUnavailableException(
                "Kast synchronized the Gradle checkout at $externalProjectPath, but its compiler model did not become usable. " +
                "IDEA reported ${latestModel.moduleNames.size} modules, " +
                "${latestModel.kotlinSourceModuleNames.size} Kotlin source modules, and " +
                "${latestModel.compilerReadyKotlinModuleNames.size} compiler-ready Kotlin modules. " +
                "Unready Kotlin modules: ${latestModel.unavailableKotlinModuleNames.joinToString().ifEmpty { "<none discovered>" }}. " +
                "The indexer must not advertise READY until Gradle is idle and Kotlin, JDK, SDK, library, and order-entry resolution are coherent.",
        )
    }

    private companion object {
        const val MODEL_READINESS_RETRY_MILLIS: Long = 250L
        const val DEFAULT_MODEL_READINESS_CHECKS: Int = 240
    }
}
