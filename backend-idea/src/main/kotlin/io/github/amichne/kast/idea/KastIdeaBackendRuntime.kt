package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.RunningAnalysisServer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RunningKastIdeaBackend internal constructor(
    val backend: AnalysisBackend,
    val server: RunningAnalysisServer,
    private val projectIndexing: KastIdeaProjectIndexing,
) : AutoCloseable {
    override fun close() {
        projectIndexing.cancel()
        server.close()
    }

    fun await() {
        server.await()
    }
}

object KastIdeaBackendRuntime {
    fun start(
        project: Project,
        workspaceRoot: Path,
        socketPath: Path = defaultSocketPath(workspaceRoot),
        config: KastConfig = KastConfig.load(workspaceRoot),
        backendName: String? = null,
    ): RunningKastIdeaBackend = start(
        project = project,
        workspaceRoot = workspaceRoot,
        transport = AnalysisTransport.UnixDomainSocket(socketPath),
        config = config,
        backendName = backendName,
    )

    fun start(
        project: Project,
        workspaceRoot: Path,
        transport: AnalysisTransport,
        config: KastConfig = KastConfig.load(workspaceRoot),
        backendName: String? = null,
    ): RunningKastIdeaBackend {
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(
            project = project,
            workspaceRoot = workspaceRoot,
            descriptorDirectory = config.paths.descriptorDir.toPath(),
        )
        KastStructuredTrace.event(
            eventName = "idea.runtime.start_requested",
            project = project,
            workspaceRoot = workspaceIdentity.workspaceRootPath,
            fields = KastStructuredTraceFields(agentRole = "idea-runtime"),
            detail = mapOf(
                "transport" to transport.toString(),
                "backendName" to backendName,
            ) + workspaceIdentity.traceDetails(),
        )
        val diagnostics = KastDiagnosticsService.getInstance(project)
        val limits = ideaServerLimits(config)
        val backend = ObservedAnalysisBackend(
            delegate = KastPluginBackend(
                project = project,
                workspaceRoot = workspaceIdentity.workspaceRootPath,
                limits = limits,
                telemetry = IdeaBackendTelemetry.fromConfig(workspaceRoot, config),
                backendName = backendName,
                workspaceIdentity = workspaceIdentity,
            ),
            diagnostics = diagnostics,
        )
        val server = AnalysisServer(
            backend = backend,
            config = ideaAnalysisServerConfig(transport, limits, config),
        ).start()
        KastStructuredTrace.event(
            eventName = "idea.runtime.server_started",
            project = project,
            workspaceRoot = workspaceIdentity.workspaceRootPath,
            fields = KastStructuredTraceFields(agentRole = "idea-runtime"),
            outcome = "completed",
            detail = mapOf("transport" to transport.toString()) + workspaceIdentity.traceDetails(),
        )
        diagnostics.recordBackendStarted(transport)
        val projectIndexing = KastIdeaProjectIndexing(project, workspaceIdentity, config, diagnostics).also { it.start() }
        return RunningKastIdeaBackend(
            backend = backend,
            server = server,
            projectIndexing = projectIndexing,
        )
    }
}

internal class KastIdeaProjectIndexing(
    private val project: Project,
    private val workspaceIdentity: IdeaWorkspaceIdentity,
    private val config: KastConfig,
    private val diagnostics: KastDiagnosticsService = KastDiagnosticsService.getInstance(project),
) {
    constructor(
        project: Project,
        workspaceRoot: Path,
        config: KastConfig,
        diagnostics: KastDiagnosticsService = KastDiagnosticsService.getInstance(project),
    ) : this(project, IdeaWorkspaceIdentity.fromProject(project, workspaceRoot, config.paths.descriptorDir.toPath()), config, diagnostics)

    private val workspaceRoot: Path = workspaceIdentity.workspaceRootPath

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var indexingThread: Thread? = null

    @Volatile
    private var indexStore: SqliteSourceIndexStore? = null

    fun start() {
        if (indexingThread != null) return
        cancelled.set(false)
        KastStructuredTrace.event(
            eventName = "idea.index.waiting_for_smart_mode",
            project = project,
            workspaceRoot = workspaceRoot,
            fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
            detail = workspaceIdentity.traceDetails(),
        )
        diagnostics.recordIndexWaitingForIde()
        DumbService.getInstance(project).runWhenSmart {
            if (cancelled.get() || project.isDisposed) return@runWhenSmart
            KastStructuredTrace.event(
                eventName = "idea.index.smart_mode_ready",
                project = project,
                workspaceRoot = workspaceRoot,
                fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
                detail = workspaceIdentity.traceDetails(),
            )
            indexingThread = thread(
                start = true,
                isDaemon = true,
                name = "kast-idea-project-indexer",
            ) {
                runCatching {
                    KastStructuredTrace.event(
                        eventName = "idea.index.hydrating",
                        project = project,
                        workspaceRoot = workspaceRoot,
                        fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
                        detail = workspaceIdentity.traceDetails(),
                    )
                    diagnostics.recordIndexHydrating()
                    runCatching {
                        SourceIndexHydrator().hydrate(workspaceRoot, config.indexing.remote)
                    }.onFailure { error ->
                        LOG.warn("Kast IDEA remote source index hydration failed", error)
                    }
                    val store = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity)
                    indexStore = store
                    KastStructuredTrace.event(
                        eventName = "idea.index.started",
                        project = project,
                        workspaceRoot = workspaceRoot,
                        fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
                        detail = workspaceIdentity.traceDetails(),
                    )
                    diagnostics.recordIndexingStarted()
                    IdeaProjectIndexer(
                        project = project,
                        workspaceRoot = workspaceRoot,
                        store = store,
                        cancelled = { cancelled.get() || Thread.currentThread().isInterrupted || project.isDisposed },
                        workspaceIdentity = workspaceIdentity.workspaceIdentity,
                    ).indexProject(config)
                    store.loadKastSourceIndexSummary()
                }.onSuccess { summary ->
                    if (!cancelled.get()) {
                        KastStructuredTrace.event(
                            eventName = "idea.index.completed",
                            project = project,
                            workspaceRoot = workspaceRoot,
                            fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
                            outcome = "completed",
                            detail = mapOf(
                                "fileCount" to summary.fileCount,
                                "identifierCount" to summary.identifierCount,
                                "moduleCount" to summary.moduleCount,
                                "importCount" to summary.importCount,
                            ) + workspaceIdentity.traceDetails(),
                        )
                        diagnostics.recordIndexCompleted(summary)
                        LOG.info("Kast IDEA project index completed")
                    }
                }.onFailure { error ->
                    if (!cancelled.get()) {
                        KastStructuredTrace.event(
                            eventName = "idea.index.failed",
                            project = project,
                            workspaceRoot = workspaceRoot,
                            fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
                            outcome = "failed",
                            detail = mapOf(
                                "errorClass" to error::class.qualifiedName,
                                "message" to error.message,
                            ) + workspaceIdentity.traceDetails(),
                        )
                        diagnostics.recordIndexFailed(error)
                        LOG.warn("Kast IDEA project index failed", error)
                    }
                }
            }
        }
    }

    fun cancel() {
        val wasRunning = indexingThread != null
        cancelled.set(true)
        indexingThread?.interrupt()
        if (!ApplicationManager.getApplication().isDispatchThread) {
            indexingThread?.join(2_000)
        }
        indexingThread = null
        indexStore?.let { store ->
            runCatching { store.close() }
                .onFailure { LOG.warn("Error closing kast project index store", it) }
        }
        indexStore = null
        if (wasRunning) {
            KastStructuredTrace.event(
                eventName = "idea.index.cancelled",
                project = project,
                workspaceRoot = workspaceRoot,
                fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
                outcome = "cancelled",
                detail = workspaceIdentity.traceDetails(),
            )
            diagnostics.recordIndexCancelled()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(KastIdeaProjectIndexing::class.java)
    }
}

private fun SqliteSourceIndexStore.loadKastSourceIndexSummary(): KastSourceIndexSummary {
    loadSourceIndexSnapshot()
    return KastSourceIndexSummary(state = KastIndexState.READY)
}
