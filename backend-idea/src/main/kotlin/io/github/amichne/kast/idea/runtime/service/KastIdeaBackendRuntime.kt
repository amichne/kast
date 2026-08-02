package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastPluginBackend
import io.github.amichne.kast.idea.diagnostics.*
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotCoordinator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.RuntimeLifecycleController
import io.github.amichne.kast.server.RuntimeProjectOpenController
import io.github.amichne.kast.server.RunningAnalysisServer
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

object KastIdeaBackendRuntime {
    fun start(
        project: Project,
        workspaceRoot: Path,
        socketPath: Path = defaultSocketPath(workspaceRoot),
        config: KastConfig = KastConfig.load(workspaceRoot),
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
            lifecycleController = lifecycleController,
            projectOpenController = projectOpenController,
            indexAdmission = KastGradleIndexAdmission.fromStartIndexing(startProjectIndexing),
        )
    }

    private fun startResolved(
        project: Project,
        workspaceIdentity: IdeaWorkspaceIdentity,
        transport: AnalysisTransport,
        config: KastConfig,
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
                "backendName" to KastPluginBackend.HEADLESS_BACKEND_NAME,
            ) + workspaceIdentity.traceDetails(),
        )
        val diagnostics = KastDiagnosticsService.getInstance(project)
        val limits = ideaServerLimits(config)
        val indexingScopeCache = WorkspaceIndexingScopeCache { error ->
            diagnostics.recordConfigFallback(workspaceIdentity.workspaceRootPath.resolve(".kastignore"), error)
        }
        val snapshotCoordinator = workspaceIdentity.workspaceIdentity.repositoryDataDirectoryPath
            ?.let { repositoryDirectory ->
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
        val manifestFileCountProvider = sourceIndexStore.prepareManifestFileCountProvider()
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
                workspaceIdentity = workspaceIdentity,
                referenceIndexLookup = DiagnosticsReferenceIndexLookup(diagnostics, sourceIndexStore),
                semanticGraphStore = sourceIndexStore,
                semanticGraphBatchSize = config.indexing.graph.batchSize,
                initialIndexingConfig = config.indexing,
                indexingConfigLoader = {
                    try {
                        KastConfig.load(workspaceIdentity.workspaceRootPath).indexing
                    } catch (error: Exception) {
                        diagnostics.recordConfigFallback(
                            workspaceIdentity.workspaceIdentity.workspaceDataDirectoryPath.resolve("config.toml"),
                            error,
                        )
                        throw error
                    }
                },
                workspaceIndexingScopeCache = indexingScopeCache,
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
                config = ideaAnalysisServerConfig(transport, limits, config, manifestFileCountProvider),
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
            val startedPluginBackend = checkNotNull(pluginBackend)
            val startedProjectIndexing = KastIdeaProjectIndexing(
                    project = project,
                    workspaceIdentity = workspaceIdentity,
                    config = config,
                    diagnostics = diagnostics,
                    indexStore = sourceIndexStore,
                    semanticAdmission = semanticAdmission,
                    snapshotCoordinator = snapshotCoordinator,
                    scopeCache = indexingScopeCache,
                    semanticGraphIndexer = { scope, batchSize ->
                        if (scope.paths.isNotEmpty() || scope.removedPaths.isNotEmpty()) {
                            startedPluginBackend.updateSemanticGraphBatchSize(batchSize)
                            runBlocking {
                                startedPluginBackend.semanticGraph(
                                    ParsedSemanticGraphQuery(
                                        filePaths = scope.paths.distinct().sorted().map { path -> path.absolute },
                                        removedFilePaths = scope.removedPaths
                                            .distinct()
                                            .sorted()
                                            .map { path -> path.absolute },
                                        expectedGeneration = null,
                                    ),
                                )
                            }
                        }
                    },
            )
            projectIndexing = startedProjectIndexing
            when (indexAdmission) {
                KastGradleIndexAdmission.Pending -> Unit
                KastGradleIndexAdmission.Ready -> startedProjectIndexing.start()
                is KastGradleIndexAdmission.Failed -> startedProjectIndexing.fail(indexAdmission.error)
            }
            return RunningKastIdeaBackend(
                backend = backend,
                server = server, workspaceRoot = workspaceIdentity.workspaceRootPath,
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

private fun Throwable.indexAdmissionFailureDetail(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.qualifiedName.orEmpty()
