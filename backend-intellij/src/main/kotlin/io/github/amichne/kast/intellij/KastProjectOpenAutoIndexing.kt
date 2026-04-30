package io.github.amichne.kast.intellij

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
            LOG.info("Kast intellij backend skipped because project has no base path")
            return false
        }

        val config = loadConfig(workspaceRoot)
        if (!config.backends.intellij.enabled) {
            LOG.info("Kast intellij backend disabled by config")
            return false
        }

        LOG.info("Kast startup activity triggered for project: ${project.name}")
        startBackendAndIndexReferences(project)
        return true
    }

    private val LOG = Logger.getInstance(KastProjectOpenAutoIndexing::class.java)
}
