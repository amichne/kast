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
        val autoInitResult = installProjectOpenProfile(workspaceRoot, config)
        KastProjectOpenProfileAutoInit.log(autoInitResult)
        if (autoInitResult is ProjectOpenProfileAutoInitResult.Failed) {
            notifyAutoInitFailure(project, autoInitResult)
        }
        if (!config.backends.idea.enabled.value) {
            LOG.info("Kast idea backend disabled by config")
            return false
        }

        LOG.info("Kast startup activity triggered for project: ${project.name}")
        startBackendAndIndexReferences(project)
        return true
    }

    private fun notifyAutoInitFailure(project: Project, result: ProjectOpenProfileAutoInitResult.Failed) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Kast")
            .createNotification(
                "Kast project setup",
                "Could not install the Copilot/LSP profile for this project: ${result.message}",
                NotificationType.WARNING,
            )
            .notify(project)
    }

    private val LOG = Logger.getInstance(KastProjectOpenAutoIndexing::class.java)
}
