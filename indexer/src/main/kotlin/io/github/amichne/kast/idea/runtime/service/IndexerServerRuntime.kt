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
import io.github.amichne.kast.change.recovery.filesystem.FilesystemAddDeclarationRecoveryPreparer
import io.github.amichne.kast.change.recovery.filesystem.FilesystemAddDeclarationRecoveryPreparerOpenResult
import io.github.amichne.kast.change.verify.intellij.IntellijPublishedWorkspaceGenerationAuthority
import io.github.amichne.kast.idea.backend.mutation.liveAddDeclarationIntellijRuntimeAuthority
import io.github.amichne.kast.idea.backend.mutation.verifiedAddDeclarationOperations
import io.github.amichne.kast.idea.transition.GitWorktreeRegistrationProof
import io.github.amichne.kast.indexer.gradle.bootstrap.InitialProjectModelAuthority
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStore
import io.github.amichne.kast.server.AnalysisServer
import io.github.amichne.kast.server.change.VerifiedAddDeclarationBinding
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
        val workspaceGenerationPublication = PersistentWorkspaceGenerationPublication(
            workspaceGenerationStore,
        )
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
        val addDeclarationBootstrap = when (
            val bootstrap = openAddDeclarationPlanPersistence(
                workspaceIdentity.workspaceIdentity.workspaceCacheDirectoryPath.resolve(
                    ADD_DECLARATION_PLAN_JOURNAL_FILE_NAME,
                ),
            )
        ) {
            is AddDeclarationPlanPersistenceBootstrap.Ready -> bootstrap
            is AddDeclarationPlanPersistenceBootstrap.Rejected -> {
                sourceIndexStore.close()
                throw AddDeclarationPlanPersistenceException.of(bootstrap.failure)
            }
        }
        val recoveryPreparer = when (val opened = FilesystemAddDeclarationRecoveryPreparer.open(
            workspaceIdentity.workspaceIdentity.workspaceCacheDirectoryPath,
        )) {
            is FilesystemAddDeclarationRecoveryPreparerOpenResult.Opened -> opened.preparer
            is FilesystemAddDeclarationRecoveryPreparerOpenResult.Rejected -> {
                sourceIndexStore.close()
                throw AddDeclarationPlanPersistenceException.of(
                    AddDeclarationPlanPersistenceFailure.STORAGE_UNAVAILABLE,
                )
            }
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
                addDeclarationPlanPersistence = addDeclarationBootstrap.persistence,
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
            val startedPluginBackend = checkNotNull(pluginBackend)
            val runtime = liveAddDeclarationIntellijRuntimeAuthority()
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
                publicSymbolReads = startedPluginBackend.nativePublicSymbolBinding(),
                verifiedAddDeclarations = VerifiedAddDeclarationBinding.Native(
                    startedPluginBackend.verifiedAddDeclarationOperations(
                        workspaceRoot = workspaceIdentity.workspaceRootPath,
                        journal = addDeclarationBootstrap.journal,
                        transitions = transitionIngress,
                        publications = IntellijPublishedWorkspaceGenerationAuthority(
                            workspaceGenerationPublication::current,
                        ),
                        recoveryPreparer = recoveryPreparer,
                        runtime = runtime,
                    ),
                ),
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
                workspaceGenerationPublication = workspaceGenerationPublication,
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

private fun Throwable.indexAdmissionFailureDetail(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.qualifiedName.orEmpty()
