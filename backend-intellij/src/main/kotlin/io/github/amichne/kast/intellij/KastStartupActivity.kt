package io.github.amichne.kast.intellij

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class KastStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (System.getenv("KAST_INTELLIJ_DISABLE") != null) {
            LOG.info("Kast intellij backend disabled by KAST_INTELLIJ_DISABLE environment variable")
            return
        }
        LOG.info("Kast startup activity triggered for project: ${project.name}")
        project.service<KastPluginService>().startServer()
    }

    companion object {
        private val LOG = Logger.getInstance(KastStartupActivity::class.java)
    }
}
