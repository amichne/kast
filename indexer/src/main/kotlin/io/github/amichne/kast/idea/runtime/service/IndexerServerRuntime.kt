package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend
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
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStore
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.RuntimeLifecycleController
import io.github.amichne.kast.server.RunningAnalysisServer
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

object IndexerServerRuntime {
    fun start(
        project: Project,
        workspaceRoot: Path,
        socketPath: Path = defaultSocketPath(workspaceRoot),
        config: KastConfig = KastConfig.load(workspaceRoot),
        lifecycleController: RuntimeLifecycleController = RuntimeLifecycleController.Unavailable,
        startProjectIndexing: Boolean = true,
    ): RunningIndexer {
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
            indexAdmission = IndexerAdmission.fromStartIndexing(startProjectIndexing),
        )
    }

    fun start(
        project: Project,
        workspaceRoot: Path,
        transport: AnalysisTransport,
        config: KastConfig = KastConfig.load(workspaceRoot),
        lifecycleController: RuntimeLifecycleController = RuntimeLifecycleController.Unavailable,
        startProjectIndexing: Boolean = true,
    ): RunningIndexer {
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
            indexAdmission = IndexerAdmission.fromStartIndexing(startProjectIndexing),
        )
    }

    private fun startResolved(
        project: Project,
        workspaceIdentity: IdeaWorkspaceIdentity,
        transport: AnalysisTransport,
        config: KastConfig,
        lifecycleController: RuntimeLifecycleController,
        indexAdmission: IndexerAdmission,
    ): RunningIndexer {
        KastStructuredTrace.event(
            eventName = "indexer.runtime.start_requested",
            project = project,
            workspaceRoot = workspaceIdentity.workspaceRootPath,
            fields = KastStructuredTraceFields(agentRole = "indexer-runtime"),
            detail = mapOf(
                "transport" to transport.toString(),
                "backendName" to KastIndexerBackend.INDEXER_NAME,
            ) + workspaceIdentity.traceDetails(),
        )
        val diagnostics = KastDiagnosticsService.getInstance(project)
        val limits = indexerServerLimits(config)
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
                producerVersion = ProducerVersion.parse(KastIndexerBackend.INDEXER_VERSION),
            )
        }
        val preparedOverlay = snapshotCoordinator?.prepareWorktreeDatabase(
            workspaceIdentity.workspaceIdentity.sourceIndexDatabaseFile,
        )
        val sourceIndexStore = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity)
        val workspaceGenerationStore = WorkspaceGenerationStore(
            directory = workspaceIdentity.workspaceIdentity.workspaceDataDirectoryPath.resolve("semantic-generations"),
            exportDatabase = sourceIndexStore::exportVerifiedWorkspaceDatabase,
        )
        preparedOverlay?.let { overlay ->
            (overlay.tombstones + overlay.shards.keys).forEach { relativePath ->
                sourceIndexStore.removeFile(workspaceIdentity.workspaceRootPath.resolve(relativePath).toString())
            }
        }
        val manifestFileCountProvider = sourceIndexStore.prepareManifestFileCountProvider()
        val semanticAdmission = IdeaIndexSemanticAdmission(project)
        if (indexAdmission is IndexerAdmission.Failed) {
            semanticAdmission.fail(indexAdmission.error.indexAdmissionFailureDetail())
        }
        var pluginBackend: KastIndexerBackend? = null
        val backend = try {
            val startedPluginBackend = KastIndexerBackend(
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
                openWorkspaceRead = semanticAdmission::openRead,
                isWorkspaceReadCurrent = semanticAdmission::isReadCurrent,
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
                config = indexerAnalysisServerConfig(transport, limits, config, manifestFileCountProvider),
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
                eventName = "indexer.runtime.server_started",
                project = project,
                workspaceRoot = workspaceIdentity.workspaceRootPath,
                fields = KastStructuredTraceFields(agentRole = "indexer-runtime"),
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
                    publishWorkspaceGeneration = { identity ->
                        workspaceGenerationStore.publish(PublishedWorkspaceIdentity(identity.value))
                    },
                    scopeCache = indexingScopeCache,
                    semanticGraphIndexer = { scope, batchSize ->
                        if (scope.paths.isNotEmpty() || scope.removedPaths.isNotEmpty()) {
                            startedPluginBackend.updateSemanticGraphBatchSize(batchSize)
                            runBlocking {
                                startedPluginBackend.reconcileSemanticGraph(
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
                IndexerAdmission.Pending -> Unit
                IndexerAdmission.Ready -> startedProjectIndexing.start()
                is IndexerAdmission.Failed -> startedProjectIndexing.fail(indexAdmission.error)
            }
            return RunningIndexer(
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
                    Logger.getInstance(IndexerServerRuntime::class.java)
                        .warn("Error closing the Kast index store after failed startup", cleanupFailure)
                },
            )?.let(failure::addSuppressed)
            throw failure
        }
    }
}

private fun Throwable.indexAdmissionFailureDetail(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.qualifiedName.orEmpty()
