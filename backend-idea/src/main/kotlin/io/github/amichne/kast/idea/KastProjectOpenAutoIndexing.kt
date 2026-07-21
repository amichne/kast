package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import java.nio.file.Path

internal object KastProjectOpenAutoIndexing {
    fun execute(
        project: Project,
        loadConfig: (Path) -> KastConfig = KastConfig::load,
        installProjectOpenProfile: (Path, KastConfig) -> ProjectOpenProfileAutoInitResult = { workspaceRoot, config ->
            KastProjectOpenProfileAutoInit.execute(workspaceRoot, config)
        },
        loadGradleProject: (Path, KastConfig) -> ProjectOpenGradleLoadResult = { workspaceRoot, config ->
            KastProjectOpenGradleLoad.execute(
                project = project,
                workspaceRoot = workspaceRoot,
                enabled = config.projectOpen.gradleLoadEnabled,
            )
        },
        startBackendAndIndexReferences: (Project) -> Unit,
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
        startBackendAndIndexReferences(project)

        if (config.projectOpen.gradleLoadEnabled.value) {
            val gradleLoadResult = loadGradleProject(workspaceRoot, config)
            KastProjectOpenGradleLoad.log(gradleLoadResult)
        } else {
            LOG.info("Kast Gradle project load skipped because projectOpen.gradleLoadEnabled is disabled")
        }
        return true
    }

    private fun notifyAutoInitFailure(project: Project, result: ProjectOpenProfileAutoInitResult.Failed) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Kast")
            .createNotification(
                "Kast project setup",
                "Could not prepare Kast for this project: ${result.message}",
                NotificationType.WARNING,
            )
            .notify(project)
    }

    private val LOG = Logger.getInstance(KastProjectOpenAutoIndexing::class.java)
}
