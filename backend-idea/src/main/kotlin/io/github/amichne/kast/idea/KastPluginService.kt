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

    @Volatile
    private var runningConfig: KastConfig? = null

    fun startServer() {
        if (runningBackend != null) return
        val workspaceRoot = workspaceRoot() ?: return
        startServer(workspaceRoot, loadConfig(workspaceRoot))
    }

    override fun dispose() {
        stopServer()
    }

    fun restartServer() {
        val workspaceRoot = workspaceRoot() ?: return
        restartServer(workspaceRoot, loadConfig(workspaceRoot))
    }

    fun reloadConfig(): KastConfigReloadDecision {
        val workspaceRoot = workspaceRoot() ?: return KastConfigReloadDecision.UNCHANGED
        val nextConfig = loadConfig(workspaceRoot)
        return when (configReloadDecision(runningConfig, nextConfig)) {
            KastConfigReloadDecision.UNCHANGED -> KastConfigReloadDecision.UNCHANGED
            KastConfigReloadDecision.RESTART_BACKEND -> {
                restartServer(workspaceRoot, nextConfig)
                KastConfigReloadDecision.RESTART_BACKEND
            }
        }
    }

    private fun restartServer(workspaceRoot: Path, config: KastConfig) {
        stopServer()
        startServer(workspaceRoot, config)
    }

    private fun startServer(workspaceRoot: Path, config: KastConfig) {
        LOG.info("Starting kast idea backend for workspace: $workspaceRoot")
        val diagnostics = KastDiagnosticsService.getInstance(project)
        diagnostics.recordBackendStarting(workspaceRoot)

        val socketPath = defaultSocketPath(workspaceRoot)
        runCatching {
            KastIdeaBackendRuntime.start(
                project = project,
                workspaceRoot = workspaceRoot,
                socketPath = socketPath,
                config = config,
            )
        }.onSuccess { backend ->
            runningBackend = backend
            runningConfig = config

            LOG.info("Kast idea backend started on socket: $socketPath")
        }.onFailure { error ->
            diagnostics.recordBackendFailed(error)
            throw error
        }
    }

    private fun stopServer() {
        runningBackend?.let { backend ->
            LOG.info("Shutting down kast idea backend")
            runCatching { backend.close() }
                .onFailure { LOG.warn("Error closing kast server", it) }
            runningBackend = null
            KastDiagnosticsService.getInstance(project).recordBackendStopped()
        }
        runningConfig = null
    }

    private fun loadConfig(workspaceRoot: Path): KastConfig = loadIdeaKastConfig(
        workspaceRoot = workspaceRoot,
        reportFailure = { path, error ->
            LOG.warn("Failed to load Kast config for workspace $path; starting IDEA backend with defaults.", error)
            KastDiagnosticsService.getInstance(project).recordConfigFallback(path, error)
        },
    )

    private fun workspaceRoot(): Path? = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }

    companion object {
        fun getInstance(project: Project): KastPluginService = project.service()

        private val LOG = Logger.getInstance(KastPluginService::class.java)
    }
}

internal fun loadIdeaKastConfig(
    workspaceRoot: Path,
    loader: (Path) -> KastConfig = KastConfig::loadIdea,
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

internal enum class KastConfigReloadDecision {
    UNCHANGED,
    RESTART_BACKEND,
}

internal fun configReloadDecision(
    current: KastConfig?,
    next: KastConfig,
): KastConfigReloadDecision =
    if (current == next) KastConfigReloadDecision.UNCHANGED else KastConfigReloadDecision.RESTART_BACKEND

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
