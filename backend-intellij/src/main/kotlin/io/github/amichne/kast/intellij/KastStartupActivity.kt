package io.github.amichne.kast.intellij

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.amichne.kast.api.client.KastConfig
import java.nio.file.Path

internal class KastStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val workspaceRoot = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
        if (workspaceRoot != null && !KastConfig.load(workspaceRoot).backends.intellij.enabled) {
            LOG.info("Kast intellij backend disabled by config")
            return
        }
        LOG.info("Kast startup activity triggered for project: ${project.name}")
        project.service<KastPluginService>().startServer()
    }

    companion object {
        private val LOG = Logger.getInstance(KastStartupActivity::class.java)
    }
}
