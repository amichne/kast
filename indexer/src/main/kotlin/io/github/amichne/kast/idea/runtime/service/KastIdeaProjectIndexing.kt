package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.kastConfigHome
import io.github.amichne.kast.api.client.fields.GraphIndexingBatchSize
import io.github.amichne.kast.idea.diagnostics.*
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparation
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver
import io.github.amichne.kast.idea.transition.IdeaGradleWorkspaceModelIdentityResolver
import io.github.amichne.kast.idea.transition.IdeaCompilerVisibleSourceIdentityResolver
import io.github.amichne.kast.idea.transition.IdeaJavaCompilerIdentityResolver
import io.github.amichne.kast.idea.transition.IdeaKotlinCompilerIdentityResolver
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentityResolver
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionGuard
import io.github.amichne.kast.idea.transition.GitWorktreeRegistrationProof
import io.github.amichne.kast.idea.transition.WorkspaceEventWakeup
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceTransitionRequest
import io.github.amichne.kast.idea.transition.WorkspaceVfsEventObserver
import io.github.amichne.kast.idea.transition.WorkspaceVfsObservationScope
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class KastIdeaProjectIndexing(
    private val project: Project,
    private val workspaceIdentity: IdeaWorkspaceIdentity,
    private val config: KastConfig,
    private val workspaceGenerationPublication: WorkspaceGenerationPublication,
    private val diagnostics: KastDiagnosticsService = KastDiagnosticsService.getInstance(project),
    private val indexStore: SqliteSourceIndexStore = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity),
    private val semanticAdmission: IdeaIndexSemanticAdmission = IdeaIndexSemanticAdmission(project),
    private val gitWorktreeRegistrationProof: GitWorktreeRegistrationProof? = null,
    private val indexingProgress: WorkspaceIndexingProgressAuthority = WorkspaceIndexingProgressAuthority(),
    private val transitionIngress: WorkspaceTransitionIngress = WorkspaceTransitionIngress(
        semanticAdmission = semanticAdmission,
        indexingProgress = indexingProgress,
    ),
    private val snapshotPreparation: RepositorySnapshotPreparation = RepositorySnapshotPreparation.Unmanaged,
    private val liveConfigLoader: (Path, KastConfig) -> KastConfig = ::loadLiveIndexingConfig,
    private val semanticGraphIndexer:
        (IndexedSourceIdentifiers, GraphIndexingBatchSize, IdeaIndexSemanticAdmission.ReconciliationToken) -> Unit =
        { _, _, _ -> },
    private val runProjectIndexing: ((KastConfig, (IndexedSourceIdentifiers) -> Unit) -> Unit)? = null,
    private val waitForNextPass: ((Long) -> Boolean)? = null,
    private val eventWakeup: WorkspaceEventWakeup = WorkspaceEventWakeup(),
    private val workspaceConfigurationFiles: Set<Path> = setOf(
        workspaceIdentity.workspaceIdentity.workspaceDataDirectoryPath.resolve("config.toml"),
        kastConfigHome().resolve("config.toml"),
    ),
    private val observeWorkspaceEvents:
        (Project, WorkspaceVfsObservationScope, (WorkspaceSignal) -> Unit) -> AutoCloseable =
        { observedProject, observedScope, observed ->
            WorkspaceVfsEventObserver.subscribe(observedProject, observedScope, observed)
        },
    private val refreshWorkspace: (Project, Path, Set<WorkspaceSignal>) -> Unit = ::refreshWorkspaceModels,
    private val resolveWorkspaceStateIdentity: (() -> WorkspaceStateIdentity)? = null,
    private val resolveBuildSemanticInputIdentity: (() -> BuildSemanticInputIdentity)? = null,
    private val scopeCache: WorkspaceIndexingScopeCache = WorkspaceIndexingScopeCache(),
) {
    private val workspaceRoot: Path = workspaceIdentity.workspaceRootPath
    private val gradleBuildRoot: Path = workspaceIdentity.workspaceIdentity.gradleRoot?.root?.toJavaPath()
        ?: workspaceRoot
    private val workspaceVfsObservationScope = WorkspaceVfsObservationScope(
        workspaceRoot = workspaceRoot,
        buildSemanticRoot = gradleBuildRoot,
        configurationFiles = workspaceConfigurationFiles,
        compilerSourceRoots = { IdeaCompilerVisibleSourceIdentityResolver.sourceRoots(project) },
        classpathRoots = {
            BuildClasspathFingerprintResolver.contentRoots(project) +
                IdeaKotlinCompilerIdentityResolver.artifactRoots(project) +
                IdeaJavaCompilerIdentityResolver.artifactRoots(project, workspaceIdentity.workspaceIdentity)
        },
    )
    private val buildSemanticInputIdentityResolver = BuildSemanticInputIdentityResolver(
        buildSemanticRoot = gradleBuildRoot,
        isCancelled = ::isCancelled,
    )
    private val cancelled = AtomicBoolean(false)
    private val runtimeReporter = WorkspaceIndexingRuntimeReporter(
        project = project,
        workspaceIdentity = workspaceIdentity,
        diagnostics = diagnostics,
        indexStore = indexStore,
        isCancelled = ::isCancelled,
    )
    private val startRequested = AtomicBoolean(false)
    private val indexingTerminated = CountDownLatch(1)
    private val lifecycleLock = Any()
    private val transitionWorkerLock = Any()
    private val bufferedWorkspaceRequests = linkedSetOf<WorkspaceTransitionRequest>()

    @Volatile
    private var indexingThread: Thread? = null

    @Volatile
    private var workspaceEventObserver: AutoCloseable? = null

    @Volatile
    private var transitionWorker: WorkspaceTransitionWorker? = null

    init {
        transitionIngress.bindRequest(::observeWorkspaceRequest)
    }

    fun start() {
        if (!startRequested.compareAndSet(false, true)) return
        if (cancelled.get()) {
            indexingTerminated.countDown()
            return
        }
        workspaceEventObserver = observeWorkspaceEvents(project, workspaceVfsObservationScope) { signal ->
            observeWorkspaceSignal(signal)
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
                        } catch (cancelledWork: ProcessCanceledException) {
                            if (!isCancelled()) {
                                semanticAdmission.fail(
                                    cancelledWork.message?.takeIf(String::isNotBlank)
                                        ?: "Workspace reconciliation was cancelled",
                                )
                                runtimeReporter.failed(cancelledWork)
                            }
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
        workspaceEventObserver?.close()
        workspaceEventObserver = null
        transitionIngress.close()
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
            runtimeReporter.failed(error)
            return
        }

        val projectIndexer = IdeaProjectIndexer(
            project = project,
            workspaceRoot = workspaceRoot,
            store = indexStore,
            cancelled = ::isCancelled,
            workspaceIdentity = workspaceIdentity.workspaceIdentity,
            indexingProgress = indexingProgress,
            scopeCache = scopeCache,
        )
        val reconciliationIndexer = WorkspaceReconciliationIndexer(
            projectIndexer = projectIndexer,
            workspaceRoot = workspaceRoot,
            indexStore = indexStore,
            scopeCache = scopeCache,
            semanticGraphIndexer = semanticGraphIndexer,
            runProjectIndexing = runProjectIndexing,
        )
        val worker = WorkspaceTransitionWorker(
            initialConfig = config,
            initialModelBuildSemanticIdentity = currentBuildSemanticInputIdentity(),
            resolveBuildSemanticInputIdentity = ::currentBuildSemanticInputIdentity,
            semanticAdmission = semanticAdmission,
            eventWakeup = eventWakeup,
            gitWorktreeTransitionGuard = GitWorktreeTransitionGuard.exactRoot(
                workspaceRoot,
                gitWorktreeRegistrationProof,
            ),
            refreshWorkspace = { signals -> refreshWorkspace(project, gradleBuildRoot, signals) },
            loadLiveConfig = { lastValid -> liveConfigLoader(workspaceRoot, lastValid) },
            captureCandidate = { liveConfig, buildSemanticInputIdentity ->
                val snapshotPublication = snapshotPreparation.capturePublication()
                resolveWorkspaceStateIdentity?.let { injected ->
                    WorkspaceReconciliationCandidate(
                        identity = injected(),
                        indexingCandidate = null,
                        snapshotPublication = snapshotPublication,
                    )
                } ?: projectIndexer.captureCandidate(liveConfig.indexing).let { candidate ->
                    WorkspaceReconciliationCandidate(
                        identity = productionWorkspaceStateIdentity(
                            project = project,
                            workspaceRoot = workspaceRoot,
                            workspaceIdentity = workspaceIdentity.workspaceIdentity,
                            liveConfig = liveConfig,
                            admittedContentIdentity = candidate.admittedContentIdentity,
                            gradleModelIdentity = IdeaGradleWorkspaceModelIdentityResolver.resolve(candidate.gradleModel),
                            buildSemanticInputIdentity = buildSemanticInputIdentity,
                            isCancelled = ::isCancelled,
                        ),
                        indexingCandidate = candidate,
                        snapshotPublication = snapshotPublication,
                    )
                }
            },
            runIndexingPass = reconciliationIndexer::run,
            workspaceGenerationPublication = workspaceGenerationPublication,
            waitForNextPass = waitForNextPass,
            isCancelled = ::isCancelled,
            onConfigFallback = { error -> diagnostics.recordConfigFallback(configPath(), error) },
            onCompleted = runtimeReporter::completed,
            onFailure = runtimeReporter::failed,
            onTransition = { snapshot ->
                transitionIngress.observe(snapshot)
                runtimeReporter.transitioned(snapshot)
            },
        )
        synchronized(transitionWorkerLock) {
            bufferedWorkspaceRequests.forEach(worker::observe)
            bufferedWorkspaceRequests.clear()
            transitionWorker = worker
        }
        worker.requestInitialReconciliation()
        try {
            worker.run()
        } finally {
            synchronized(transitionWorkerLock) {
                if (transitionWorker === worker) transitionWorker = null
            }
        }
    }

    private fun observeWorkspaceSignal(signal: WorkspaceSignal) {
        observeWorkspaceRequest(WorkspaceTransitionRequest.Unkeyed(signal))
    }

    private fun observeWorkspaceRequest(request: WorkspaceTransitionRequest) {
        semanticAdmission.dirty("workspace event requires reconciliation: ${request.signal.name}")
        routeWorkspaceRequest(
            lock = transitionWorkerLock,
            request = request,
            enqueue = { observed ->
                transitionWorker?.observe(observed) ?: run { bufferedWorkspaceRequests += observed }
            },
            wake = eventWakeup::signal,
        )
    }

    private fun isCancelled(): Boolean =
        cancelled.get() || Thread.currentThread().isInterrupted || project.isDisposed

    private fun configPath(): Path =
        workspaceIdentity.workspaceIdentity.workspaceDataDirectoryPath.resolve("config.toml")

    fun fail(error: Throwable) {
        semanticAdmission.fail(error.message?.takeIf(String::isNotBlank) ?: error::class.java.name)
        diagnostics.recordIndexFailed(error)
    }

    private fun currentBuildSemanticInputIdentity(): BuildSemanticInputIdentity =
        resolveBuildSemanticInputIdentity?.invoke() ?: buildSemanticInputIdentityResolver.resolve()

    companion object {
        private val LOG = Logger.getInstance(KastIdeaProjectIndexing::class.java)
    }
}
