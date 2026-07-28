package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.diagnostics.*
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotCoordinator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.RuntimeLifecycleController
import io.github.amichne.kast.server.RuntimeProjectOpenController
import io.github.amichne.kast.server.RunningAnalysisServer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RunningKastIdeaBackend internal constructor(
    val backend: CloseableAnalysisBackend,
    val server: RunningAnalysisServer,
    private val projectIndexing: KastIdeaProjectIndexing,
    private val sourceIndexStore: SqliteSourceIndexStore,
) : AutoCloseable, KastIdeaBackendHandle {
    private val closeCompletion = AtomicReference<CompletableFuture<Unit>?>(null)
    override fun close() {
        val completion = closeAsync()
        if (!ApplicationManager.getApplication().isDispatchThread) {
            try {
                completion.join()
            } catch (failure: CompletionException) {
                throw failure.cause ?: failure
            }
        }
    }
    override fun closeAsync(): CompletableFuture<Unit> {
        closeCompletion.get()?.let { return it }
        val completion = CompletableFuture<Unit>()
        if (!closeCompletion.compareAndSet(null, completion)) {
            return checkNotNull(closeCompletion.get())
        }
        val cancellationFailure = runCatching(projectIndexing::cancel).exceptionOrNull()
        completeOnBackgroundThread(
            completion = completion,
            threadName = "kast-idea-backend-closer",
        ) {
            var firstFailure = cancellationFailure
            listOf<() -> Unit>(
                server::close,
                {
                    projectIndexing.awaitTermination()
                    sourceIndexStore.close()
                },
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
        completion.whenComplete { _, failure ->
            if (failure != null) {
                LOG.warn("Error closing Kast IDEA backend", unwrapCloseFailure(failure))
            }
        }
        return completion
    }
    fun await() {
        server.await()
    }
    override fun startIndexing() {
        projectIndexing.start()
    }
    override fun failIndexing(error: Throwable) {
        projectIndexing.fail(error)
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(RunningKastIdeaBackend::class.java)
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
        projectOpenController: RuntimeProjectOpenController = RuntimeProjectOpenController.Unavailable,
        startProjectIndexing: Boolean = true,
    ): RunningKastIdeaBackend {
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(
            project = project,
            workspaceRoot = workspaceRoot,
            descriptorDirectory = config.paths.descriptorDir.toPath(),
        )
        return startResolved(
            project = project,
            workspaceIdentity = workspaceIdentity,
            transport = AnalysisTransport.UnixDomainSocket(socketPath),
            config = config,
            backendName = backendName,
            lifecycleController = lifecycleController,
            projectOpenController = projectOpenController,
            indexAdmission = KastGradleIndexAdmission.fromStartIndexing(startProjectIndexing),
        )
    }

    fun start(
        project: Project,
        workspaceRoot: Path,
        transport: AnalysisTransport,
        config: KastConfig = KastConfig.load(workspaceRoot),
        backendName: String? = null,
        lifecycleController: RuntimeLifecycleController = RuntimeLifecycleController.Unavailable,
        projectOpenController: RuntimeProjectOpenController = RuntimeProjectOpenController.Unavailable,
        startProjectIndexing: Boolean = true,
    ): RunningKastIdeaBackend {
        val workspaceIdentity = IdeaWorkspaceIdentity.fromProject(
            project = project,
            workspaceRoot = workspaceRoot,
            descriptorDirectory = config.paths.descriptorDir.toPath(),
        )
        return startResolved(
            project = project,
            workspaceIdentity = workspaceIdentity,
            transport = transport,
            config = config,
            backendName = backendName,
            lifecycleController = lifecycleController,
            projectOpenController = projectOpenController,
            indexAdmission = KastGradleIndexAdmission.fromStartIndexing(startProjectIndexing),
        )
    }

    internal fun startPrepared(
        project: Project,
        workspaceIdentity: IdeaWorkspaceIdentity,
        socketPath: Path,
        config: KastConfig,
        lifecycleController: RuntimeLifecycleController,
        projectOpenController: RuntimeProjectOpenController,
        indexAdmission: KastGradleIndexAdmission,
    ): RunningKastIdeaBackend = startResolved(
        project = project,
        workspaceIdentity = workspaceIdentity,
        transport = AnalysisTransport.UnixDomainSocket(socketPath),
        config = config,
        backendName = null,
        lifecycleController = lifecycleController,
        projectOpenController = projectOpenController,
        indexAdmission = indexAdmission,
    )

    private fun startResolved(
        project: Project,
        workspaceIdentity: IdeaWorkspaceIdentity,
        transport: AnalysisTransport,
        config: KastConfig,
        backendName: String?,
        lifecycleController: RuntimeLifecycleController,
        projectOpenController: RuntimeProjectOpenController,
        indexAdmission: KastGradleIndexAdmission,
    ): RunningKastIdeaBackend {
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
        val snapshotCoordinator = workspaceIdentity.workspaceIdentity.repositoryDataDirectoryPath?.let { repositoryDirectory ->
            RepositorySnapshotCoordinator(
                workspaceRoot = workspaceIdentity.workspaceRootPath,
                repositoryDirectory = repositoryDirectory,
                buildClasspathFingerprint = BuildClasspathFingerprintResolver.resolve(
                    project,
                    workspaceIdentity.workspaceIdentity,
                ),
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
        if (indexAdmission is KastGradleIndexAdmission.Failed) {
            semanticAdmission.fail(indexAdmission.error.indexAdmissionFailureDetail())
        }
        var pluginBackend: KastPluginBackend? = null
        val backend = try {
            val startedPluginBackend = KastPluginBackend(
                project = project,
                workspaceRoot = workspaceIdentity.workspaceRootPath,
                limits = limits,
                telemetry = IdeaBackendTelemetry.fromConfig(workspaceIdentity.workspaceRootPath, config),
                backendName = backendName,
                workspaceIdentity = workspaceIdentity,
                referenceIndexLookup = DiagnosticsReferenceIndexLookup(diagnostics, sourceIndexStore),
                semanticGraphStore = sourceIndexStore,
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
                projectOpenController = projectOpenController,
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
            )
            projectIndexing = startedProjectIndexing
            when (indexAdmission) {
                KastGradleIndexAdmission.Pending -> Unit
                KastGradleIndexAdmission.Ready -> startedProjectIndexing.start()
                is KastGradleIndexAdmission.Failed -> startedProjectIndexing.fail(indexAdmission.error)
            }
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
            ).forEach { cleanupPhase ->
                try {
                    cleanupPhase()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            closeSourceIndexStoreAfterIndexing(
                projectIndexing = projectIndexing,
                sourceIndexStore = sourceIndexStore,
                onAsyncFailure = { cleanupFailure ->
                    failure.addSuppressed(cleanupFailure)
                    Logger.getInstance(KastIdeaBackendRuntime::class.java)
                        .warn("Error closing Kast source index store after failed startup", cleanupFailure)
                },
            )?.let(failure::addSuppressed)
            throw failure
        }
    }
}

private fun closeSourceIndexStoreAfterIndexing(
    projectIndexing: KastIdeaProjectIndexing?,
    sourceIndexStore: SqliteSourceIndexStore,
    onAsyncFailure: (Throwable) -> Unit,
): Throwable? {
    return closeAfterLeavingIdeaDispatchThread(
        threadName = "kast-idea-source-index-closer",
        onAsyncFailure = onAsyncFailure,
    ) {
        projectIndexing?.awaitTermination()
        sourceIndexStore.close()
    }
}

internal fun closeAfterLeavingIdeaDispatchThread(
    threadName: String,
    onAsyncFailure: (Throwable) -> Unit,
    close: () -> Unit,
): Throwable? {
    if (!ApplicationManager.getApplication().isDispatchThread) {
        return runCatching(close).exceptionOrNull()
    }
    val completion = closeAfterLeavingIdeaDispatchThreadAsync(threadName, close)
    completion.whenComplete { _, failure ->
        if (failure != null) onAsyncFailure(unwrapCloseFailure(failure))
    }
    return null
}

internal fun closeAfterLeavingIdeaDispatchThreadAsync(
    threadName: String,
    close: () -> Unit,
): CompletableFuture<Unit> = CompletableFuture<Unit>().also { completion ->
    completeOnBackgroundThread(completion, threadName, close)
}

private fun completeOnBackgroundThread(
    completion: CompletableFuture<Unit>,
    threadName: String,
    close: () -> Unit,
) {
    try {
        thread(
            start = true,
            isDaemon = true,
            name = threadName,
        ) {
            completeNow(completion, close)
        }
    } catch (failure: Throwable) {
        completion.completeExceptionally(failure)
    }
}

private fun completeNow(
    completion: CompletableFuture<Unit>,
    close: () -> Unit,
) {
    val complete = {
        try {
            close()
            completion.complete(Unit)
        } catch (failure: Throwable) {
            completion.completeExceptionally(failure)
        }
        Unit
    }
    complete()
}

private fun Throwable.indexAdmissionFailureDetail(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.qualifiedName.orEmpty()

private fun unwrapCloseFailure(failure: Throwable): Throwable =
    if (failure is CompletionException && failure.cause != null) {
        checkNotNull(failure.cause)
    } else {
        failure
    }
