package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.diagnostics.KastDiagnosticsService
import java.nio.file.Path

internal object KastProjectOpenAutoIndexing {
    fun execute(
        project: Project,
        loadConfig: (Path) -> KastConfig = KastConfig::load,
        installProjectOpenProfile: (Path, KastConfig) -> ProjectOpenProfileAutoInitResult = { workspaceRoot, config ->
            KastProjectOpenProfileAutoInit.execute(workspaceRoot, config)
        },
        loadGradleProject: (Path, KastConfig, (Throwable?) -> Unit) -> ProjectOpenGradleLoadResult =
            { workspaceRoot, config, onComplete ->
            KastProjectOpenGradleLoad.execute(
                project = project,
                workspaceRoot = workspaceRoot,
                enabled = config.projectOpen.gradleLoadEnabled,
                onComplete = onComplete,
            )
        },
        startBackend: (Project) -> Unit,
        startReferenceIndex: (Project) -> Unit,
        failReadiness: (Project, Throwable) -> Unit,
    ): Boolean {
        val workspaceRoot = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        if (workspaceRoot == null) {
            LOG.info("Kast idea backend skipped because project has no base path")
            return false
        }
        if (System.getProperty("kast.idea.autostart") == "false") {
            LOG.info("Kast idea backend skipped because plugin autostart is disabled")
            return false
        }

        val config = loadConfig(workspaceRoot)
        if (!config.backends.idea.enabled.value) {
            LOG.info("Kast idea backend disabled by config")
            return false
        }
        val autoInitResult = installProjectOpenProfile(workspaceRoot, config)
        KastProjectOpenProfileAutoInit.log(autoInitResult)
        when (autoInitResult) {
            is ProjectOpenProfileAutoInitResult.Installed -> {}
            is ProjectOpenProfileAutoInitResult.Skipped -> {
                LOG.info("Kast idea backend skipped because workspace setup did not run: ${autoInitResult.reason}")
                return false
            }
            is ProjectOpenProfileAutoInitResult.Failed -> {
                notifyAutoInitFailure(project, autoInitResult)
                return false
            }
        }

        LOG.info("Kast startup activity triggered for project: ${project.name}")
        startBackend(project)

        if (config.projectOpen.gradleLoadEnabled.value) {
            val gradleLoadResult = loadGradleProject(workspaceRoot, config) { failure ->
                if (failure == null) {
                    startReferenceIndex(project)
                } else {
                    failReadiness(project, failure)
                }
            }
            KastProjectOpenGradleLoad.log(gradleLoadResult)
            when (gradleLoadResult) {
                is ProjectOpenGradleLoadResult.Requested -> {}
                is ProjectOpenGradleLoadResult.Skipped -> startReferenceIndex(project)
                is ProjectOpenGradleLoadResult.Failed ->
                    failReadiness(project, IllegalStateException(gradleLoadResult.message))
            }
        } else {
            LOG.info("Kast Gradle project load skipped because projectOpen.gradleLoadEnabled is disabled")
            startReferenceIndex(project)
        }
        return true
    }

    private fun notifyAutoInitFailure(project: Project, result: ProjectOpenProfileAutoInitResult.Failed) {
        KastDiagnosticsService.getInstance(project).notifyTerminalFailure(
            title = "Kast project setup failed",
            detail = "Could not prepare Kast for this project: ${result.message}. Run `kast setup` and retry.",
        )
    }

    private val LOG = Logger.getInstance(KastProjectOpenAutoIndexing::class.java)
}
