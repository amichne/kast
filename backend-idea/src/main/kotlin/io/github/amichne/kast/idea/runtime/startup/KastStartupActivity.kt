package io.github.amichne.kast.idea

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRoot
import java.nio.file.Path

internal class KastStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val workspaceRoot = project.basePath
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?: return
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
        if (canonicalRoot != null) {
            project.service<KastPluginService>().observeProjectOpenSignals(canonicalRoot, config)
        }
        KastProjectOpenAutoIndexing.execute(
            project = project,
            config = config,
            startBackend = { startupProject, startupConfig ->
                startupProject.service<KastPluginService>().startServer(
                    config = startupConfig,
                    startIndexing = false,
                )
            },
            startReferenceIndex = { startupProject ->
                startupProject.service<KastPluginService>().startIndexing()
            },
            restartBackend = { startupProject ->
                startupProject.service<KastPluginService>().restartServer()
            },
        )
    }
}
