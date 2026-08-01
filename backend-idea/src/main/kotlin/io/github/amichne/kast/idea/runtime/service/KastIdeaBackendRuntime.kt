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
import io.github.amichne.kast.api.contract.query.SemanticGraphPath
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStoreAccess
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
        val indexAccess = persistedIndexAccess(backendName)
        val indexingScopeCache = WorkspaceIndexingScopeCache { error ->
            diagnostics.recordConfigFallback(workspaceIdentity.workspaceRootPath.resolve(".kastignore"), error)
        }
        val snapshotCoordinator = workspaceIdentity.workspaceIdentity.repositoryDataDirectoryPath
            ?.takeIf { indexAccess == PersistedIndexAccess.READ_WRITE }
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
        val sourceIndexStore = SqliteSourceIndexStore(
            workspaceIdentity.workspaceIdentity,
            when (indexAccess) {
                PersistedIndexAccess.READ_ONLY -> SqliteSourceIndexStoreAccess.READ_ONLY
                PersistedIndexAccess.READ_WRITE -> SqliteSourceIndexStoreAccess.READ_WRITE
            },
        )
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
                backendName = backendName,
                workspaceIdentity = workspaceIdentity,
                referenceIndexLookup = DiagnosticsReferenceIndexLookup(diagnostics, sourceIndexStore),
                semanticGraphStore = sourceIndexStore,
                semanticGraphBatchSize = config.indexing.graph.batchSize.value,
                persistedIndexAccess = indexAccess,
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
            val startedProjectIndexing = if (persistedProjectIndexingEnabled(backendName)) {
                KastIdeaProjectIndexing(
                    project = project,
                    workspaceIdentity = workspaceIdentity,
                    config = config,
                    diagnostics = diagnostics,
                    indexStore = sourceIndexStore,
                    semanticAdmission = semanticAdmission,
                    snapshotCoordinator = snapshotCoordinator,
                    scopeCache = indexingScopeCache,
                    semanticGraphIndexer = { paths, batchSize ->
                        if (paths.isNotEmpty()) {
                            startedPluginBackend.updateSemanticGraphBatchSize(batchSize)
                            runBlocking {
                                startedPluginBackend.semanticGraph(
                                    ParsedSemanticGraphQuery(
                                        filePaths = paths.distinct().sorted().map(SemanticGraphPath::parse),
                                        removedFilePaths = emptyList(),
                                        expectedGeneration = null,
                                    ),
                                )
                            }
                        }
                    },
                )
            } else {
                null
            }
            projectIndexing = startedProjectIndexing
            if (startedProjectIndexing != null) {
                when (indexAdmission) {
                    KastGradleIndexAdmission.Pending -> Unit
                    KastGradleIndexAdmission.Ready -> startedProjectIndexing.start()
                    is KastGradleIndexAdmission.Failed -> startedProjectIndexing.fail(indexAdmission.error)
                }
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

internal fun persistedProjectIndexingEnabled(backendName: String?): Boolean = backendName == "headless"

internal enum class PersistedIndexAccess {
    READ_ONLY,
    READ_WRITE,
}

internal fun persistedIndexAccess(backendName: String?): PersistedIndexAccess =
    if (persistedProjectIndexingEnabled(backendName)) PersistedIndexAccess.READ_WRITE else PersistedIndexAccess.READ_ONLY

private fun Throwable.indexAdmissionFailureDetail(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.qualifiedName.orEmpty()
