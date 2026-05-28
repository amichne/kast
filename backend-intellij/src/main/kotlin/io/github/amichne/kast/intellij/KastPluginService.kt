package io.github.amichne.kast.intellij

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
    private var runningBackend: RunningKastIntelliJBackend? = null

    fun startServer() {
        if (runningBackend != null) return
        val basePath = project.basePath ?: return
        val workspaceRoot = Path.of(basePath).toAbsolutePath().normalize()

        LOG.info("Starting kast intellij backend for workspace: $workspaceRoot")

        val kastConfig = KastConfig.load(workspaceRoot)
        val socketPath = defaultSocketPath(workspaceRoot)
        runningBackend = KastIntelliJBackendRuntime.start(
            project = project,
            workspaceRoot = workspaceRoot,
            socketPath = socketPath,
            config = kastConfig,
        )

        LOG.info("Kast intellij backend started on socket: $socketPath")
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
            LOG.info("Shutting down kast intellij backend")
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

internal fun intellijServerLimits(config: KastConfig): ServerLimits = ServerLimits(
    maxConcurrentRequests = config.server.maxConcurrentRequests.value.coerceAtLeast(1),
    requestTimeoutMillis = config.server.requestTimeoutMillis.value,
    maxResults = config.server.maxResults.value,
)

internal fun intellijAnalysisServerConfig(
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

internal fun intellijAnalysisServerConfig(
    socketPath: Path,
    limits: ServerLimits,
    config: KastConfig,
): AnalysisServerConfig = intellijAnalysisServerConfig(
    transport = AnalysisTransport.UnixDomainSocket(socketPath),
    limits = limits,
    config = config,
)
