package io.github.amichne.kast.intellij

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class KastStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        KastProjectOpenAutoIndexing.execute(project) { startupProject ->
            startupProject.service<KastPluginService>().startServer()
        }
    }
}
