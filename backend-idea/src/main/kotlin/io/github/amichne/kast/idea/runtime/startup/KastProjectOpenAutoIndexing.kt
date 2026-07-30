package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import java.nio.file.Path

internal object KastProjectOpenAutoIndexing {
    fun execute(
        project: Project,
        config: KastConfig,
        loadGradleProject: (Path, KastConfig, (Throwable?) -> Unit) -> ProjectOpenGradleLoadResult =
            { workspaceRoot, config, onComplete ->
                KastProjectOpenGradleLoad.execute(
                    project = project,
                    workspaceRoot = workspaceRoot,
                    enabled = config.projectOpen.gradleLoadEnabled,
                    onComplete = onComplete,
                )
            },
        startBackend: (Project, KastConfig) -> Unit,
        startReferenceIndex: (Project) -> Unit,
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

        if (!config.backends.idea.enabled.value) {
            LOG.info("Kast idea backend disabled by config")
            return false
        }

        LOG.info("Kast startup activity triggered for project: ${project.name}")
        val backendStartFailure = runCatching {
            startBackend(project, config)
        }.exceptionOrNull()
        if (backendStartFailure != null) {
            LOG.warn("Kast IDEA backend startup failed for $workspaceRoot", backendStartFailure)
            return false
        }
        startReferenceIndex(project)

        if (config.projectOpen.gradleLoadEnabled.value) {
            val gradleLoadResult = loadGradleProject(workspaceRoot, config) {}
            KastProjectOpenGradleLoad.log(gradleLoadResult)
        } else {
            LOG.info("Kast Gradle project load skipped because projectOpen.gradleLoadEnabled is disabled")
        }
        return true
    }

    private val LOG = Logger.getInstance(KastProjectOpenAutoIndexing::class.java)
}
