package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import java.nio.file.Path

internal object KastProjectOpenAutoIndexing {
    fun execute(
        project: Project,
        loadConfig: (Path) -> KastConfig = KastConfig::load,
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

        LOG.info("Kast startup activity triggered for project: ${project.name}")
        startBackendAndIndexReferences(project)
        return true
    }

    private val LOG = Logger.getInstance(KastProjectOpenAutoIndexing::class.java)
}
