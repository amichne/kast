package io.github.amichne.kast.idea

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRoot
import java.nio.file.Path

internal class KastStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.basePath
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?.let { workspaceRoot ->
                val config = loadIdeaKastConfig(workspaceRoot)
                val canonicalRoot = runCatching {
                    RuntimeOpenProjectRoot.of(workspaceRoot)
                }.getOrNull()
                if (
                    canonicalRoot != null &&
                    KastOpenProjectRequestStore(config).consumeUntargetedForProject(canonicalRoot)
                ) {
                    KastOpenedProjectProvenance.mark(project)
                }
            }
        KastProjectOpenAutoIndexing.execute(
            project = project,
            startBackend = { startupProject ->
                startupProject.service<KastPluginService>().startServer(startIndexing = false)
            },
            startReferenceIndex = { startupProject ->
                startupProject.service<KastPluginService>().startIndexing()
            },
            failReadiness = { startupProject, error ->
                startupProject.service<KastPluginService>().failIndexing(error)
            },
        )
    }
}
