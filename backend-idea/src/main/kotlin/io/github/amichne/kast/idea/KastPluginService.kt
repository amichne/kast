package io.github.amichne.kast.idea

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.server.AnalysisServerConfig
import java.nio.file.Path

@Service(Service.Level.PROJECT)
internal class KastPluginService(
    private val project: Project,
) : Disposable {

    @Volatile
    private var runningBackend: RunningKastIdeaBackend? = null

    fun startServer() {
        if (runningBackend != null) return
        val basePath = project.basePath ?: return
        val workspaceRoot = Path.of(basePath).toAbsolutePath().normalize()

        LOG.info("Starting kast idea backend for workspace: $workspaceRoot")

        val kastConfig = loadIdeaKastConfig(workspaceRoot)
        val socketPath = defaultSocketPath(workspaceRoot)
        runningBackend = KastIdeaBackendRuntime.start(
            project = project,
            workspaceRoot = workspaceRoot,
            socketPath = socketPath,
            config = kastConfig,
        )

        LOG.info("Kast idea backend started on socket: $socketPath")
    }

    override fun dispose() {
        stopServer()
    }

    fun restartServer() {
        stopServer()
        startServer()
    }

    private fun stopServer() {
        runningBackend?.let { backend ->
            LOG.info("Shutting down kast idea backend")
            runCatching { backend.close() }
                .onFailure { LOG.warn("Error closing kast server", it) }
            runningBackend = null
        }
    }

    companion object {
        fun getInstance(project: Project): KastPluginService = project.service()

        private val LOG = Logger.getInstance(KastPluginService::class.java)
    }
}

internal fun loadIdeaKastConfig(
    workspaceRoot: Path,
    loader: (Path) -> KastConfig = KastConfig::load,
    reportFailure: (Path, Exception) -> Unit = { path, error ->
        Logger.getInstance(KastPluginService::class.java).warn(
            "Failed to load Kast config for workspace $path; starting IDEA backend with defaults.",
            error,
        )
    },
): KastConfig =
    try {
        loader(workspaceRoot)
    } catch (error: Exception) {
        reportFailure(workspaceRoot, error)
        KastConfig.defaults()
    }

internal fun ideaServerLimits(config: KastConfig): ServerLimits = ServerLimits(
    maxConcurrentRequests = config.server.maxConcurrentRequests.value.coerceAtLeast(1),
    requestTimeoutMillis = config.server.requestTimeoutMillis.value,
    maxResults = config.server.maxResults.value,
)

internal fun ideaAnalysisServerConfig(
    transport: AnalysisTransport,
    limits: ServerLimits,
    config: KastConfig,
): AnalysisServerConfig = AnalysisServerConfig(
    transport = transport,
    requestTimeoutMillis = limits.requestTimeoutMillis,
    maxResults = limits.maxResults,
    maxConcurrentRequests = limits.maxConcurrentRequests,
    descriptorDirectory = config.paths.descriptorDir.toPath(),
)

internal fun ideaAnalysisServerConfig(
    socketPath: Path,
    limits: ServerLimits,
    config: KastConfig,
): AnalysisServerConfig = ideaAnalysisServerConfig(
    transport = AnalysisTransport.UnixDomainSocket(socketPath),
    limits = limits,
    config = config,
)
