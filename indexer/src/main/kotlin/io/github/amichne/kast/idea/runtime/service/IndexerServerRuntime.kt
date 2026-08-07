package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.diagnostics.*
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotCoordinator
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparation
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparationException
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparationResolution
import io.github.amichne.kast.idea.snapshot.WorktreeOverlaySeed
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.client.WorkspaceRepository
import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.api.contract.AnalysisTransport
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.idea.transition.GitWorktreeRegistrationProof
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
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
            registrationProof = null,
            runtimeInstanceId = null,
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
            registrationProof = null,
            runtimeInstanceId = null,
        )
    }

    internal fun startWithRegistrationProof(
        project: Project,
        workspaceRoot: Path,
        transport: AnalysisTransport,
        config: KastConfig,
        registrationProof: GitWorktreeRegistrationProof?,
        runtimeInstanceId: RuntimeInstanceId?,
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
            lifecycleController = RuntimeLifecycleController.Unavailable,
            indexAdmission = IndexerAdmission.fromStartIndexing(true),
            registrationProof = registrationProof,
            runtimeInstanceId = runtimeInstanceId,
        )
    }

    private fun startResolved(
        project: Project,
        workspaceIdentity: IdeaWorkspaceIdentity,
        transport: AnalysisTransport,
        config: KastConfig,
        lifecycleController: RuntimeLifecycleController,
        indexAdmission: IndexerAdmission,
        registrationProof: GitWorktreeRegistrationProof?,
        runtimeInstanceId: RuntimeInstanceId?,
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
        val snapshotPreparation = when (val repository = workspaceIdentity.workspaceIdentity.repository) {
            WorkspaceRepository.None -> RepositorySnapshotPreparation.Unmanaged
            is WorkspaceRepository.Git -> when (
                val resolution = RepositorySnapshotCoordinator.prepare(
                    workspaceRoot = workspaceIdentity.workspaceIdentity.canonicalWorkspaceRoot,
                    repositoryDirectory = repository.dataDirectory,
                    workspaceDatabase = workspaceIdentity.workspaceIdentity.sourceIndexDatabasePath,
                    buildClasspathFingerprint = BuildClasspathFingerprintResolver.resolve(
                        project,
                        workspaceIdentity.workspaceIdentity,
                    ),
                    producerVersion = ProducerVersion.fromVersion(KastIndexerBackend.INDEXER_VERSION),
                )
            ) {
                is RepositorySnapshotPreparationResolution.Resolved -> resolution.preparation
                is RepositorySnapshotPreparationResolution.Rejected ->
                    throw RepositorySnapshotPreparationException(resolution.failure)
            }
        }
        val sourceIndexStore = SqliteSourceIndexStore(workspaceIdentity.workspaceIdentity)
        sourceIndexStore.ensureSchema()
        val workspaceGenerationStore = WorkspaceGenerationStore(sourceIndexStore)
        when (val overlay = snapshotPreparation.overlaySeed) {
            is WorktreeOverlaySeed.None -> Unit
            is WorktreeOverlaySeed.Prepared -> {
                (overlay.manifest.tombstones + overlay.manifest.shards.keys).forEach { relativePath ->
                    sourceIndexStore.removeFile(
                        workspaceIdentity.workspaceRootPath.resolve(relativePath.value).toString(),
                    )
                }
            }
        }
        val manifestFileCountProvider = sourceIndexStore.prepareManifestFileCountProvider()
        val semanticAdmission = IdeaIndexSemanticAdmission(project)
        val transitionIngress = WorkspaceTransitionIngress(semanticAdmission)
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
                workspaceSemanticReadAuthority = semanticAdmission,
                workspaceTransitionRequester = transitionIngress,
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
                config = indexerAnalysisServerConfig(
                    transport = transport,
                    runtimeInstanceId = runtimeInstanceId,
                    limits = limits,
                    config = config,
                    workspaceFileCountProvider = manifestFileCountProvider,
                ),
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
                    workspaceGenerationPublication = PersistentWorkspaceGenerationPublication(workspaceGenerationStore),
                    diagnostics = diagnostics,
                    indexStore = sourceIndexStore,
                    semanticAdmission = semanticAdmission,
                    gitWorktreeRegistrationProof = registrationProof,
                    transitionIngress = transitionIngress,
                    snapshotPreparation = snapshotPreparation,
                    scopeCache = indexingScopeCache,
                    semanticGraphIndexer = { scope, batchSize, reconciliationToken ->
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
                                    reconciliationToken,
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
