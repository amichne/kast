package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.diagnostics.*
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotCoordinator

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.RuntimeLifecycleController
import io.github.amichne.kast.server.RunningAnalysisServer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RunningKastIdeaBackend internal constructor(
    val backend: CloseableAnalysisBackend,
    val server: RunningAnalysisServer,
    private val projectIndexing: KastIdeaProjectIndexing,
    private val sourceIndexStore: SqliteSourceIndexStore,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        var firstFailure: Throwable? = null
        listOf<() -> Unit>(
            projectIndexing::cancel,
            server::close,
            sourceIndexStore::close,
        ).forEach { closePhase ->
            try {
                closePhase()
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else {
                    firstFailure.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { failure -> throw failure }
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
        lifecycleController: RuntimeLifecycleController = RuntimeLifecycleController.Unavailable,
    ): RunningKastIdeaBackend = start(
        project = project,
        workspaceRoot = workspaceRoot,
        transport = AnalysisTransport.UnixDomainSocket(socketPath),
        config = config,
        backendName = backendName,
        lifecycleController = lifecycleController,
    )

    fun start(
        project: Project,
        workspaceRoot: Path,
        transport: AnalysisTransport,
        config: KastConfig = KastConfig.load(workspaceRoot),
        backendName: String? = null,
        lifecycleController: RuntimeLifecycleController = RuntimeLifecycleController.Unavailable,
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
        val buildClasspathFingerprint = BuildClasspathFingerprintResolver.resolve(
            project,
            workspaceIdentity.workspaceIdentity,
        )
        val snapshotCoordinator = workspaceIdentity.workspaceIdentity.repositoryDataDirectoryPath?.let { repositoryDirectory ->
            RepositorySnapshotCoordinator(
                workspaceRoot = workspaceIdentity.workspaceRootPath,
                repositoryDirectory = repositoryDirectory,
                buildClasspathFingerprint = buildClasspathFingerprint,
                producerVersion = ProducerVersion.parse(KastPluginBackend.BACKEND_VERSION),
            )
        }
        val preparedOverlay = snapshotCoordinator?.prepareWorktreeDatabase(
            workspaceIdentity.workspaceIdentity.sourceIndexDatabaseFile,
        )
        val sourceIndexStore = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity)
        preparedOverlay?.let { overlay ->
            (overlay.tombstones + overlay.shards.keys).forEach { relativePath ->
                sourceIndexStore.removeFile(workspaceIdentity.workspaceRootPath.resolve(relativePath).toString())
            }
        }
        val semanticAdmission = IdeaIndexSemanticAdmission(project)
        var pluginBackend: KastPluginBackend? = null
        val backend = try {
            val startedPluginBackend = KastPluginBackend(
                project = project,
                workspaceRoot = workspaceIdentity.workspaceRootPath,
                limits = limits,
                telemetry = IdeaBackendTelemetry.fromConfig(workspaceRoot, config),
                backendName = backendName,
                workspaceIdentity = workspaceIdentity,
                referenceIndexLookup = DiagnosticsReferenceIndexLookup(diagnostics, sourceIndexStore),
                semanticGraphStore = sourceIndexStore,
                semanticGraphConfigurationFingerprint = buildClasspathFingerprint,
                indexSemanticAdmissionStatus = semanticAdmission::status,
            )
            pluginBackend = startedPluginBackend
            ObservedAnalysisBackend(
                delegate = startedPluginBackend,
                diagnostics = diagnostics,
            )
        } catch (failure: Throwable) {
            listOf<() -> Unit>(
                { pluginBackend?.close() },
                sourceIndexStore::close,
            ).forEach { cleanupPhase ->
                try {
                    cleanupPhase()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
        val server = try {
            AnalysisServer(
                backend = backend,
                config = ideaAnalysisServerConfig(transport, limits, config),
                lifecycleController = lifecycleController,
            ).start()
        } catch (failure: Throwable) {
            listOf<() -> Unit>(backend::close, sourceIndexStore::close).forEach { cleanupPhase ->
                try {
                    cleanupPhase()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }

        var projectIndexing: KastIdeaProjectIndexing? = null
        try {
            KastStructuredTrace.event(
                eventName = "idea.runtime.server_started",
                project = project,
                workspaceRoot = workspaceIdentity.workspaceRootPath,
                fields = KastStructuredTraceFields(agentRole = "idea-runtime"),
                outcome = "completed",
                detail = mapOf("transport" to transport.toString()) + workspaceIdentity.traceDetails(),
            )
            diagnostics.recordBackendStarted(transport)
            val startedProjectIndexing = KastIdeaProjectIndexing(
                project = project,
                workspaceIdentity = workspaceIdentity,
                config = config,
                diagnostics = diagnostics,
                indexStore = sourceIndexStore,
                semanticAdmission = semanticAdmission,
                snapshotCoordinator = snapshotCoordinator,
            ).also { it.start() }
            projectIndexing = startedProjectIndexing
            return RunningKastIdeaBackend(
                backend = backend,
                server = server,
                projectIndexing = startedProjectIndexing,
                sourceIndexStore = sourceIndexStore,
            )
        } catch (failure: Throwable) {
            listOf<() -> Unit>(
                { projectIndexing?.cancel() },
                server::close,
                sourceIndexStore::close,
            ).forEach { cleanupPhase ->
                try {
                    cleanupPhase()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }
}

internal class KastIdeaProjectIndexing(
    private val project: Project,
    private val workspaceIdentity: IdeaWorkspaceIdentity,
    private val config: KastConfig,
    private val diagnostics: KastDiagnosticsService = KastDiagnosticsService.getInstance(project),
    private val indexStore: SqliteSourceIndexStore = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity),
    private val semanticAdmission: IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(project),
    private val snapshotCoordinator: RepositorySnapshotCoordinator? = null,
) {
    constructor(
        project: Project,
        workspaceRoot: Path,
        config: KastConfig,
        diagnostics: KastDiagnosticsService = KastDiagnosticsService.getInstance(project),
    ) : this(
        project,
        IdeaWorkspaceIdentity.fromProject(project, workspaceRoot, config.paths.descriptorDir.toPath()),
        config,
        diagnostics,
    )

    private val workspaceRoot: Path = workspaceIdentity.workspaceRootPath

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var indexingThread: Thread? = null

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
                    semanticAdmission.await {
                        cancelled.get() || Thread.currentThread().isInterrupted || project.isDisposed
                    }
                    runCatching {
                        SourceIndexHydrator().hydrate(workspaceRoot, config.indexing.remote)
                    }.onFailure { error ->
                        LOG.warn("Kast IDEA remote source index hydration failed", error)
                    }
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
                        store = indexStore,
                        cancelled = { cancelled.get() || Thread.currentThread().isInterrupted || project.isDisposed },
                        workspaceIdentity = workspaceIdentity.workspaceIdentity,
                    ).indexProject(config)
                    indexStore.loadKastSourceIndexSummary()
                }.onSuccess { summary ->
                    if (!cancelled.get()) {
                        snapshotCoordinator?.let { coordinator ->
                            runCatching {
                                coordinator.publishCompletedIndex(indexStore)
                            }.onFailure { error ->
                                LOG.warn("Kast repository snapshot publication failed", error)
                            }
                        }
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
        runCatching { indexStore.close() }
            .onFailure { LOG.warn("Error closing kast project index store", it) }
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
    val snapshot = loadSourceIndexSnapshot()
    return KastSourceIndexSummary(
        state = KastIndexState.READY,
        fileCount = snapshot.packageByPath.size,
        identifierCount = snapshot.candidatePathsByIdentifier.size,
        moduleCount = snapshot.moduleNameByPath.values
            .asSequence()
            .filter(String::isNotBlank)
            .map { moduleName -> moduleName.substringBefore("[") }
            .distinct()
            .count(),
        importCount = snapshot.importsByPath.values.sumOf(List<String>::size) +
            snapshot.wildcardImportPackagesByPath.values.sumOf(List<String>::size),
    )
}
