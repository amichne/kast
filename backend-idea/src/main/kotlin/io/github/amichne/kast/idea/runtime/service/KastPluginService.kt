package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.diagnostics.*

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.socketPathForWorkspaceRoot
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRoot
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.RuntimeLifecycleController
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
internal class KastPluginService(
    private val project: Project,
) : Disposable {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var projectOpenRequestObserver: KastOpenProjectRequestObserver? = null
    private val backendLifecycle = KastPluginBackendLifecycle(
        startBackend = ::createBackend,
        onStopping = ::recordBackendStopping,
        onStopCompleted = ::recordBackendStopCompleted,
        onAsyncStartFailed = { workspaceRoot, error ->
            LOG.warn("Kast IDEA backend restart failed for $workspaceRoot", error)
        },
    )

    fun startServer(startIndexing: Boolean = true) {
        val workspaceRoot = workspaceRoot() ?: return
        backendLifecycle.start(
            workspaceRoot = workspaceRoot,
            config = loadConfig(workspaceRoot),
            initialAdmission = KastGradleIndexAdmission.fromStartIndexing(startIndexing),
        )
    }

    fun startServer(config: KastConfig, startIndexing: Boolean = true) {
        val workspaceRoot = workspaceRoot() ?: return
        backendLifecycle.start(
            workspaceRoot = workspaceRoot,
            config = config,
            initialAdmission = KastGradleIndexAdmission.fromStartIndexing(startIndexing),
        )
    }

    override fun dispose() {
        coroutineScope.cancel()
        backendLifecycle.dispose()
    }

    fun restartServer() {
        val workspaceRoot = workspaceRoot() ?: return
        backendLifecycle.restart(workspaceRoot, loadConfig(workspaceRoot))
    }

    fun startIndexing() = backendLifecycle.markIndexReady()

    fun failIndexing(error: Throwable) = backendLifecycle.markIndexFailed(error)

    fun observeProjectOpenSignals(canonicalRoot: RuntimeOpenProjectRoot, config: KastConfig) {
        val observer = KastOpenProjectRequestObserver(
            requests = KastOpenProjectRequestStore(config),
            canonicalRoot = canonicalRoot,
            onSignal = {
                KastOpenedProjectProvenance.mark(project)
                startServer()
            },
        )
        projectOpenRequestObserver = observer
        coroutineScope.launch { observer.run() }
    }

    fun exploreAsync(
        request: KastExplorerRequest,
        deliver: (KastExplorerResult) -> Unit,
    ) {
        coroutineScope.launch {
            val result = runCatching { backendLifecycle.explore(request) }
                .getOrElse { error ->
                    KastExplorerResult.Problem(
                        io.github.amichne.kast.api.contract.NonBlankString(
                            error.message?.takeIf(String::isNotBlank)
                                ?: error::class.simpleName
                                ?: "Kast explorer query failed",
                        ),
                    )
                }
            if (!project.isDisposed) {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) deliver(result)
                }
            }
        }
    }

    fun reloadConfigAsync() {
        coroutineScope.launch {
            reloadConfig()
        }
    }

    fun reloadConfig(): KastConfigReloadDecision {
        val workspaceRoot = workspaceRoot() ?: return KastConfigReloadDecision.UNCHANGED
        val nextConfig = loadConfig(workspaceRoot)
        return backendLifecycle.reload(workspaceRoot, nextConfig)
    }

    private fun createBackend(start: KastPluginBackendStart): KastIdeaBackendHandle {
        throw UnsupportedOperationException(
            "IDEA_SEMANTIC_BACKEND_RETIRED: foreground IDEA cannot start a Kast semantic runtime for ${start.workspaceRoot}",
        )
    }

    private fun stopServer() {
        backendLifecycle.stop()
    }

    private fun recordBackendStopping(workspaceRoot: Path) {
        LOG.info("Shutting down kast idea backend")
        KastStructuredTrace.event(
            eventName = "idea.backend.stopping",
            project = project,
            workspaceRoot = workspaceRoot,
            fields = KastStructuredTraceFields(agentRole = "idea-plugin"),
        )
    }

    private fun recordBackendStopCompleted(workspaceRoot: Path, failure: Throwable?) {
        if (failure == null) {
            KastStructuredTrace.event(
                eventName = "idea.backend.stopped",
                project = project,
                workspaceRoot = workspaceRoot,
                fields = KastStructuredTraceFields(agentRole = "idea-plugin"),
                outcome = "completed",
            )
            KastDiagnosticsService.getInstance(project).recordBackendStopped()
            return
        }
        KastStructuredTrace.event(
            eventName = "idea.backend.stop_failed",
            project = project,
            workspaceRoot = workspaceRoot,
            fields = KastStructuredTraceFields(agentRole = "idea-plugin"),
            outcome = "failed",
            detail = mapOf(
                "errorClass" to failure::class.qualifiedName,
                "message" to failure.message,
            ),
        )
        LOG.warn("Error closing kast server", failure)
    }

    private fun lifecycleController(): RuntimeLifecycleController = RuntimeLifecycleController { action ->
        when (action) {
            io.github.amichne.kast.api.contract.RuntimeLifecycleAction.SHUTDOWN -> ::stopServer
            io.github.amichne.kast.api.contract.RuntimeLifecycleAction.RESTART -> ::restartServer
        }
    }

    private fun loadConfig(workspaceRoot: Path): KastConfig = loadIdeaKastConfig(
        workspaceRoot = workspaceRoot,
        reportFailure = { path, error ->
            LOG.warn("Failed to load Kast config for workspace $path; starting IDEA backend with defaults.", error)
            KastDiagnosticsService.getInstance(project).recordConfigFallback(path, error)
        },
    ).also { config ->
        projectOpenRequestObserver?.replaceRequests(KastOpenProjectRequestStore(config))
    }

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
        KastStructuredTrace.event(
            eventName = "idea.config.fallback",
            workspaceRoot = workspaceRoot,
            fields = KastStructuredTraceFields(agentRole = "idea-plugin"),
            outcome = "failed",
            detail = mapOf(
                "errorClass" to error::class.qualifiedName,
                "message" to error.message,
            ),
        )
        reportFailure(workspaceRoot, error)
        KastConfig.defaults()
    }

internal enum class KastConfigReloadDecision {
    UNCHANGED,
    START_BACKEND,
    STOP_BACKEND,
    RESTART_BACKEND,
}

internal fun configReloadDecision(
    current: KastConfig?,
    next: KastConfig,
): KastConfigReloadDecision {
    if (!next.backends.idea.enabled.value) {
        return if (current?.backends?.idea?.enabled?.value == true) {
            KastConfigReloadDecision.STOP_BACKEND
        } else {
            KastConfigReloadDecision.UNCHANGED
        }
    }
    if (current == null || !current.backends.idea.enabled.value) {
        return KastConfigReloadDecision.START_BACKEND
    }
    return if (
        current.server != next.server ||
        current.indexing != next.indexing ||
        current.telemetry != next.telemetry ||
        current.paths != next.paths
    ) {
        KastConfigReloadDecision.RESTART_BACKEND
    } else {
        KastConfigReloadDecision.UNCHANGED
    }
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
): AnalysisServerConfig = ideaAnalysisServerConfig(transport, limits, config, workspaceFileCount = 0)

internal fun ideaAnalysisServerConfig(
    transport: AnalysisTransport,
    limits: ServerLimits,
    config: KastConfig,
    workspaceFileCountProvider: () -> Int,
): AnalysisServerConfig = ideaAnalysisServerConfig(
    transport = transport,
    limits = limits,
    config = config,
    workspaceFileCount = workspaceFileCountProvider(),
    workspaceFileCountProvider = workspaceFileCountProvider,
)

internal fun ideaAnalysisServerConfig(
    transport: AnalysisTransport,
    limits: ServerLimits,
    config: KastConfig,
    workspaceFileCount: Int,
    workspaceFileCountProvider: (() -> Int)? = null,
): AnalysisServerConfig = AnalysisServerConfig(
    transport = transport,
    requestTimeoutMillis = limits.requestTimeoutMillis,
    maxResults = limits.maxResults,
    maxConcurrentRequests = limits.maxConcurrentRequests,
    continuationTtlMillis = limits.continuationTtlMillis,
    continuationCapacity = limits.continuationCapacity,
    descriptorDirectory = config.paths.descriptorDir.toPath(),
    workspaceFileCount = workspaceFileCount,
    workspaceFileCountProvider = workspaceFileCountProvider,
)

internal fun ideaAnalysisServerConfig(
    socketPath: Path,
    limits: ServerLimits,
    config: KastConfig,
): AnalysisServerConfig = ideaAnalysisServerConfig(socketPath, limits, config, workspaceFileCount = 0)

internal fun ideaAnalysisServerConfig(
    socketPath: Path,
    limits: ServerLimits,
    config: KastConfig,
    workspaceFileCount: Int,
): AnalysisServerConfig = ideaAnalysisServerConfig(
    transport = AnalysisTransport.UnixDomainSocket(socketPath),
    limits = limits,
    config = config,
    workspaceFileCount = workspaceFileCount,
)
