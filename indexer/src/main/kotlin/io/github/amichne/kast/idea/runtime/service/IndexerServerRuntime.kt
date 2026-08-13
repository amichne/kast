package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.workspace.nativePublicSymbolBinding
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
import io.github.amichne.kast.api.contract.RuntimeCapabilityLeaseRegistry
import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceException
import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceFailure
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournalOpenFailure
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournalOpenResult
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistence
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistenceService
import io.github.amichne.kast.idea.transition.GitWorktreeRegistrationProof
import io.github.amichne.kast.indexer.gradle.bootstrap.InitialProjectModelAuthority
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStore
import io.github.amichne.kast.server.AnalysisServer
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

object IndexerServerRuntime {
    fun start(
        project: Project,
        workspaceRoot: Path,
        socketPath: Path = defaultSocketPath(workspaceRoot),
        config: KastConfig = KastConfig.load(workspaceRoot),
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
            indexAdmission = IndexerAdmission.fromStartIndexing(startProjectIndexing),
            registrationProof = null,
            runtimeInstanceId = null,
            initialProjectModelAuthority = InitialProjectModelAuthority.Unverified,
        )
    }

    fun start(
        project: Project,
        workspaceRoot: Path,
        transport: AnalysisTransport,
        config: KastConfig = KastConfig.load(workspaceRoot),
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
            indexAdmission = IndexerAdmission.fromStartIndexing(startProjectIndexing),
            registrationProof = null,
            runtimeInstanceId = null,
            initialProjectModelAuthority = InitialProjectModelAuthority.Unverified,
        )
    }

    internal fun startWithRegistrationProof(
        project: Project,
        workspaceRoot: Path,
        transport: AnalysisTransport,
        config: KastConfig,
        registrationProof: GitWorktreeRegistrationProof?,
        runtimeInstanceId: RuntimeInstanceId?,
        initialProjectModelAuthority: InitialProjectModelAuthority,
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
            indexAdmission = IndexerAdmission.fromStartIndexing(true),
            registrationProof = registrationProof,
            runtimeInstanceId = runtimeInstanceId,
            initialProjectModelAuthority = initialProjectModelAuthority,
        )
    }

    private fun startResolved(
        project: Project,
        workspaceIdentity: IdeaWorkspaceIdentity,
        transport: AnalysisTransport,
        config: KastConfig,
        indexAdmission: IndexerAdmission,
        registrationProof: GitWorktreeRegistrationProof?,
        runtimeInstanceId: RuntimeInstanceId?,
        initialProjectModelAuthority: InitialProjectModelAuthority,
    ): RunningIndexer {
        val admittedRuntimeInstanceId = runtimeInstanceId ?: RuntimeInstanceId.create()
        val runtimeCapabilityLeases = RuntimeCapabilityLeaseRegistry(
            admittedRuntimeInstanceId,
        )
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
        val indexingProgress = WorkspaceIndexingProgressAuthority()
        val transitionIngress = WorkspaceTransitionIngress(
            semanticAdmission = semanticAdmission,
            indexingProgress = indexingProgress,
        )
        if (indexAdmission is IndexerAdmission.Failed) {
            semanticAdmission.fail(indexAdmission.error.indexAdmissionFailureDetail())
        }
        var pluginBackend: KastIndexerBackend? = null
        val backend = try {
            val addDeclarationPlanPersistence = when (
                val bootstrap = openAddDeclarationPlanPersistence(
                    workspaceIdentity.workspaceIdentity.workspaceCacheDirectoryPath.resolve(
                        ADD_DECLARATION_PLAN_JOURNAL_FILE_NAME,
                    ),
                )
            ) {
                is AddDeclarationPlanPersistenceBootstrap.Ready -> bootstrap.persistence
                is AddDeclarationPlanPersistenceBootstrap.Rejected ->
                    throw AddDeclarationPlanPersistenceException.of(bootstrap.failure)
            }
            val startedPluginBackend = KastIndexerBackend(
                project = project,
                workspaceRoot = workspaceIdentity.workspaceRootPath,
                limits = limits,
                telemetry = IdeaBackendTelemetry.fromConfig(workspaceIdentity.workspaceRootPath, config),
                workspaceIdentity = workspaceIdentity,
                referenceIndexLookup = DiagnosticsReferenceIndexLookup(diagnostics, sourceIndexStore),
                semanticGraphStore = sourceIndexStore,
                semanticGraphBatchSize = config.indexing.graph.batchSize,
                workspaceIndexingProgress = indexingProgress,
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
                runtimeCapabilityLeases = runtimeCapabilityLeases,
                addDeclarationPlanPersistence = addDeclarationPlanPersistence,
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
                    runtimeInstanceId = admittedRuntimeInstanceId,
                    limits = limits,
                    config = config,
                    workspaceFileCountProvider = manifestFileCountProvider,
                    runtimeCapabilityLeases = runtimeCapabilityLeases,
                ),
                publicSymbolReads = checkNotNull(pluginBackend).nativePublicSymbolBinding(),
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
                initialProjectModelAuthority = initialProjectModelAuthority,
                gitWorktreeRegistrationProof = registrationProof,
                indexingProgress = indexingProgress,
                transitionIngress = transitionIngress,
                snapshotPreparation = snapshotPreparation,
                scopeCache = indexingScopeCache,
                semanticGraphIndexer = SemanticGraphIndexingTransition { input ->
                    val scope = input.sourceIdentifiers
                    if (scope.paths.isNotEmpty() || scope.removedPaths.isNotEmpty()) {
                        startedPluginBackend.updateSemanticGraphBatchSize(input.batchSize)
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
                                input.reconciliationToken,
                            )
                        }
                    }
                    GraphLaneOutcome.Committed
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

internal const val ADD_DECLARATION_PLAN_JOURNAL_FILE_NAME = "add-declaration-plans.db"

internal sealed interface AddDeclarationPlanPersistenceBootstrap {
    data class Ready(
        val persistence: AddDeclarationPlanPersistence,
    ) : AddDeclarationPlanPersistenceBootstrap

    data class Rejected(
        val failure: AddDeclarationPlanPersistenceFailure,
    ) : AddDeclarationPlanPersistenceBootstrap
}

/**
 * Proof transition: `Path -> AddDeclarationPlanPersistenceBootstrap`.
 *
 * A ready result establishes an initialized workspace-scoped SQLite journal whose bootstrap
 * connection is closed and which exposes only detached plan persistence. The closed expected
 * failure is `AddDeclarationPlanPersistenceFailure`; the raw database path is extracted only by
 * the SQLite journal adapter.
 */
internal fun openAddDeclarationPlanPersistence(
    databasePath: Path,
): AddDeclarationPlanPersistenceBootstrap =
    when (val opened = SqliteAddDeclarationPlanJournal.open(databasePath)) {
        is SqliteAddDeclarationPlanJournalOpenResult.Opened ->
            AddDeclarationPlanPersistenceBootstrap.Ready(
                AddDeclarationPlanPersistenceService(opened.journal),
            )
        is SqliteAddDeclarationPlanJournalOpenResult.Rejected ->
            AddDeclarationPlanPersistenceBootstrap.Rejected(
                when (opened.failure) {
                    is SqliteAddDeclarationPlanJournalOpenFailure.InvalidDatabasePath ->
                        AddDeclarationPlanPersistenceFailure.DATABASE_PATH_INVALID
                    SqliteAddDeclarationPlanJournalOpenFailure.StorageUnavailable ->
                        AddDeclarationPlanPersistenceFailure.STORAGE_UNAVAILABLE
                },
            )
    }

private fun Throwable.indexAdmissionFailureDetail(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.qualifiedName.orEmpty()
