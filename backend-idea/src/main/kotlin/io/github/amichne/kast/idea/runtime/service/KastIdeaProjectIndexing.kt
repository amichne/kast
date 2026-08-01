package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.idea.diagnostics.*
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotCoordinator
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class KastIdeaProjectIndexing(
    private val project: Project,
    private val workspaceIdentity: IdeaWorkspaceIdentity,
    private val config: KastConfig,
    private val diagnostics: KastDiagnosticsService = KastDiagnosticsService.getInstance(project),
    private val indexStore: SqliteSourceIndexStore = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity),
    private val semanticAdmission: IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(project),
    private val snapshotCoordinator: RepositorySnapshotCoordinator? = null,
    private val liveConfigLoader: (Path, KastConfig) -> KastConfig = ::loadLiveIndexingConfig,
    private val semanticGraphIndexer: (List<String>, Int) -> Unit = { _, _ -> },
    private val runProjectIndexing: ((KastConfig, (List<String>) -> Unit) -> Unit)? = null,
    private val waitForNextPass: (Long) -> Boolean = ::waitForIndexingRetry,
    private val scopeCache: WorkspaceIndexingScopeCache = WorkspaceIndexingScopeCache(),
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
    private val startRequested = AtomicBoolean(false)
    private val indexingTerminated = CountDownLatch(1)
    private val lifecycleLock = Any()

    @Volatile
    private var indexingThread: Thread? = null

    fun start() {
        if (!startRequested.compareAndSet(false, true)) return
        if (cancelled.get()) {
            indexingTerminated.countDown()
            return
        }
        KastStructuredTrace.event(
            eventName = "idea.index.waiting_for_smart_mode",
            project = project,
            workspaceRoot = workspaceRoot,
            fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
            detail = workspaceIdentity.traceDetails(),
        )
        diagnostics.recordIndexWaitingForIde()
        DumbService.getInstance(project).runWhenSmart {
            val worker = synchronized(lifecycleLock) {
                if (cancelled.get() || project.isDisposed) {
                    indexingTerminated.countDown()
                    null
                } else {
                    thread(
                        start = false,
                        isDaemon = true,
                        name = "kast-idea-project-indexer",
                    ) {
                        try {
                            runIndexing()
                        } finally {
                            indexingTerminated.countDown()
                        }
                    }.also { indexingThread = it }
                }
            } ?: return@runWhenSmart
            KastStructuredTrace.event(
                eventName = "idea.index.smart_mode_ready",
                project = project,
                workspaceRoot = workspaceRoot,
                fields = KastStructuredTraceFields(agentRole = "idea-indexer"),
                detail = workspaceIdentity.traceDetails(),
            )
            runCatching(worker::start).onFailure { error ->
                indexingTerminated.countDown()
                diagnostics.recordIndexFailed(error)
                LOG.warn("Kast IDEA project index worker failed to start", error)
            }
        }
    }

    fun cancel() {
        val wasRunning = startRequested.get() && indexingTerminated.count > 0
        cancelled.set(true)
        val worker = synchronized(lifecycleLock) {
            indexingThread.also {
                if (it == null && startRequested.get()) {
                    indexingTerminated.countDown()
                }
            }
        }
        worker?.interrupt()
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

    fun awaitTermination() {
        if (!startRequested.get()) return
        var interrupted = false
        while (true) {
            try {
                indexingTerminated.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun runIndexing() {
        val prepared = runCatching {
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
        }
        prepared.exceptionOrNull()?.let { error ->
            recordIndexFailure(error)
            return
        }

        val projectIndexer = IdeaProjectIndexer(
            project = project,
            workspaceRoot = workspaceRoot,
            store = indexStore,
            cancelled = ::isCancelled,
            workspaceIdentity = workspaceIdentity.workspaceIdentity,
            scopeCache = scopeCache,
        )
        var lastValidConfig = config
        var consecutiveFailures = 0
        while (!isCancelled()) {
            val candidate = try {
                liveConfigLoader(workspaceRoot, lastValidConfig)
            } catch (error: Exception) {
                diagnostics.recordConfigFallback(configPath(), error)
                lastValidConfig
            }
            var pass = runCatching { runIndexingPass(projectIndexer, candidate) }
            val scopeFailure = pass.exceptionOrNull() as? IndexingScopeConfigurationException
            if (scopeFailure != null && candidate != lastValidConfig) {
                diagnostics.recordConfigFallback(configPath(), scopeFailure)
                pass = runCatching { runIndexingPass(projectIndexer, lastValidConfig) }
            }

            pass.onSuccess { result ->
                lastValidConfig = candidate.takeUnless { scopeFailure != null } ?: lastValidConfig
                consecutiveFailures = if (result.graphFailure == null) 0 else consecutiveFailures + 1
                recordIndexCompleted(result.summary)
            }.onFailure { error ->
                consecutiveFailures += 1
                recordIndexFailure(error)
            }
            if (isCancelled()) break
            val delay = if (pass.getOrNull()?.graphFailure == null && pass.isSuccess) {
                PERIODIC_INDEXING_RETRY_MILLIS
            } else {
                indexingRetryDelayMillis(consecutiveFailures)
            }
            if (!waitForNextPass(delay)) break
        }
    }

    private fun runIndexingPass(
        projectIndexer: IdeaProjectIndexer,
        liveConfig: KastConfig,
    ): IndexingPassResult {
        var graphFailure: Throwable? = null
        val graph: (List<String>) -> Unit = { paths ->
            runCatching {
                semanticGraphIndexer(paths, liveConfig.indexing.graph.batchSize.value)
            }.onFailure { error ->
                graphFailure = error
                LOG.warn("Kast semantic graph indexing pass failed", error)
            }
        }
        val indexedSources = runProjectIndexing?.let { indexProject ->
            indexProject(liveConfig, graph)
            scopeCache.resolve(
                workspaceRoot = workspaceRoot,
                config = liveConfig.indexing,
                candidates = indexStore.knownSourcePaths(),
            ).let { scope ->
                IndexedSourceIdentifiers(
                    paths = scope.includedPaths.map(Path::toString),
                    criticalPaths = scope.criticalPaths.mapTo(linkedSetOf(), Path::toString),
                    unmatchedCriticalPatterns = scope.unmatchedCriticalPatterns,
                )
            }
        } ?: projectIndexer.indexProject(liveConfig, graph)
        return IndexingPassResult(
            summary = indexStore.loadKastSourceIndexSummary(
                criticalPaths = indexedSources.criticalPaths,
                unmatchedCriticalPatterns = indexedSources.unmatchedCriticalPatterns,
            ),
            graphFailure = graphFailure,
        )
    }

    private fun recordIndexCompleted(summary: KastSourceIndexSummary) {
        if (isCancelled()) return
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

    private fun recordIndexFailure(error: Throwable) {
        if (isCancelled()) return
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

    private fun isCancelled(): Boolean =
        cancelled.get() || Thread.currentThread().isInterrupted || project.isDisposed

    private fun configPath(): Path =
        workspaceIdentity.workspaceIdentity.workspaceDataDirectoryPath.resolve("config.toml")

    fun fail(error: Throwable) {
        semanticAdmission.fail(error.message?.takeIf(String::isNotBlank) ?: error::class.java.name)
        diagnostics.recordIndexFailed(error)
    }

    companion object {
        private val LOG = Logger.getInstance(KastIdeaProjectIndexing::class.java)
    }
}

private data class IndexingPassResult(
    val summary: KastSourceIndexSummary,
    val graphFailure: Throwable?,
)

private const val PERIODIC_INDEXING_RETRY_MILLIS = 30_000L

internal fun indexingRetryDelayMillis(consecutiveFailures: Int): Long = when (consecutiveFailures) {
    1 -> 250L
    2 -> 500L
    3 -> 1_000L
    else -> PERIODIC_INDEXING_RETRY_MILLIS
}

private fun loadLiveIndexingConfig(
    workspaceRoot: Path,
    lastValid: KastConfig,
): KastConfig = lastValid.copy(indexing = KastConfig.load(workspaceRoot).indexing)

private fun waitForIndexingRetry(delayMillis: Long): Boolean = try {
    Thread.sleep(delayMillis)
    true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}
