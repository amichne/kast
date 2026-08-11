package io.github.amichne.kast.indexer.gradle.bootstrap

import com.intellij.openapi.project.Project
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelReadiness
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelSettlementEvidence
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelUnavailableException
import io.github.amichne.kast.indexer.project.ProjectModelBootstrapResult
import io.github.amichne.kast.indexer.project.WorkspaceKind
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentityResolver
import java.nio.file.Path

class GradleProjectBootstrap internal constructor(
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
    private val captureBuildSemanticInputIdentity: (Project, Path) -> BuildSemanticInputIdentity = { _, root ->
        buildSemanticInputIdentity(root)
    },
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
            if (latestModel.compilerReady && hasLinkedGradleProject(externalProjectPath, project)) {
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

    /**
     * Proof transition: `(Project, Path, WorkspaceKind) -> BootstrappedProjectModel`.
     *
     * Runs the existing project-model bootstrap and binds its imported model to
     * the exact build-semantic input identity captured after the bootstrap. The
     * returned authority permits reuse only when build definitions were stable
     * across the bootstrap interval; movement is retained as the closed
     * [InitialProjectModelAuthority.Unverified] state.
     */
    internal fun bootstrapProject(
        project: Project,
        workspaceRoot: Path,
        workspaceKind: WorkspaceKind,
    ): BootstrappedProjectModel {
        val beforeBootstrap = captureBuildSemanticInputIdentity(project, workspaceRoot)
        val result = bootstrap(project, workspaceRoot, workspaceKind)
        val afterBootstrap = captureBuildSemanticInputIdentity(project, workspaceRoot)
        return BootstrappedProjectModel(
            result = result,
            initialProjectModelAuthority = when (result) {
                is ProjectModelBootstrapResult.Ready -> importedProjectModelAuthority(
                    result,
                    beforeBootstrap,
                    afterBootstrap,
                )
                is ProjectModelBootstrapResult.Skipped -> InitialProjectModelAuthority.Unverified
            },
        )
    }

    private companion object {
        const val MODEL_READINESS_RETRY_MILLIS: Long = 250L
        const val DEFAULT_MODEL_READINESS_CHECKS: Int = 240
    }
}

internal data class BootstrappedProjectModel(
    val result: ProjectModelBootstrapResult,
    val initialProjectModelAuthority: InitialProjectModelAuthority,
)

/** Closed authority for the project model available before initial reconciliation. */
internal sealed interface InitialProjectModelAuthority {
    data object Unverified : InitialProjectModelAuthority {
        override fun <T> fold(
            onUnverified: () -> T,
            onImported: (BuildSemanticInputIdentity) -> T,
        ): T = onUnverified()
    }

    fun <T> fold(
        onUnverified: () -> T,
        onImported: (BuildSemanticInputIdentity) -> T,
    ): T
}

private class ImportedProjectModelAuthority(
    private val importedModel: BuildSemanticInputIdentity,
) : InitialProjectModelAuthority {
    override fun <T> fold(
        onUnverified: () -> T,
        onImported: (BuildSemanticInputIdentity) -> T,
    ): T = onImported(importedModel)
}

/**
 * Proof transition:
 * `(ProjectModelBootstrapResult.Ready, BuildSemanticInputIdentity, BuildSemanticInputIdentity)`
 * `-> InitialProjectModelAuthority`.
 *
 * This transition is private to the bootstrap effect owner. Equal samples
 * establish that the carried after-bootstrap identity spans a Ready import
 * interval with stable build definitions; movement yields [InitialProjectModelAuthority.Unverified].
 */
private fun importedProjectModelAuthority(
    ready: ProjectModelBootstrapResult.Ready,
    beforeBootstrap: BuildSemanticInputIdentity,
    afterBootstrap: BuildSemanticInputIdentity,
): InitialProjectModelAuthority = if (ready.linkedGradleProject && beforeBootstrap == afterBootstrap) {
    ImportedProjectModelAuthority(afterBootstrap)
} else {
    InitialProjectModelAuthority.Unverified
}

private fun buildSemanticInputIdentity(workspaceRoot: Path): BuildSemanticInputIdentity =
    BuildSemanticInputIdentityResolver(buildSemanticRoot(workspaceRoot)).resolve()

private fun buildSemanticRoot(workspaceRoot: Path): Path =
    WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot).gradleRoot?.root?.toJavaPath()
        ?: workspaceRoot.toAbsolutePath().normalize()
