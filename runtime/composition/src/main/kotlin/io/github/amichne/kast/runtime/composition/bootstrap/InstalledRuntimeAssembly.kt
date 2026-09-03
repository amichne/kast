package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.intellij.InstalledIntellijChangePorts
import io.github.amichne.kast.diagnostic.intellij.installedIntellijDiagnosticCompiler
import io.github.amichne.kast.evidence.sqlite.SqliteCanonicalWorkspacePublicationTransaction
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournal
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournalOpenResult
import io.github.amichne.kast.evidence.sqlite.SqliteWorkspacePublicationDatabase
import io.github.amichne.kast.evidence.sqlite.SqliteWorkspacePublicationDatabaseOpening
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStore
import io.github.amichne.kast.evidence.sqlite.SqliteTopologySnapshotStoreOpening
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.intellij.InstalledRelationScopeOperations
import io.github.amichne.kast.relation.intellij.installedIntellijRelationCompiler
import io.github.amichne.kast.runtime.composition.change.InstalledChangePlanningAdmission
import io.github.amichne.kast.runtime.composition.change.InstalledChangeVerificationObserver
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelBoundary
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelRead
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelReadOperations
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleWorkspaceModel
import io.github.amichne.kast.runtime.composition.platform.InstalledWorkspaceModelAdapter
import io.github.amichne.kast.runtime.composition.platform.projectInstalledGradleModel
import io.github.amichne.kast.runtime.composition.protocol.CanonicalKastOperationHandlerFactory
import io.github.amichne.kast.symbol.intellij.InstalledIntellijSymbolPorts
import io.github.amichne.kast.symbol.intellij.InstalledSymbolScopeOperations
import io.github.amichne.kast.source.intellij.InstalledIntellijSourceReadPort
import io.github.amichne.kast.workspace.contract.WorkspacePublicationRun
import io.github.amichne.kast.workspace.intellij.InstalledIntellijWorkspace
import io.github.amichne.kast.workspace.intellij.InstalledIntellijWorkspaceBootstrapObserver
import io.github.amichne.kast.workspace.intellij.InstalledIntellijWorkspaceBootstrapPhase
import io.github.amichne.kast.workspace.intellij.InstalledIntellijWorkspaceModel
import io.github.amichne.kast.workspace.intellij.InstalledIntellijWorkspaceOpening
import io.github.amichne.kast.workspace.intellij.IntellijWorkspaceReconciliationPort
import io.github.amichne.kast.workspace.service.WorkspacePublicationCoordinator
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.intellij.intellijSynchronizedTopologyCandidateEnumerator
import io.github.amichne.kast.topology.intellij.intellijSourceRootIndexRefresh
import io.github.amichne.kast.topology.intellij.installedIntellijTopologyExtractor
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import java.util.concurrent.ForkJoinPool

/** Closed construction inputs whose live platform values remain behind narrow adapter ports. */
internal data class InstalledRuntimeAssemblyInputs(
    val workspaceModel: InstalledGradleModelReadOperations,
    val semantic: SemanticRuntimePorts,
    val topologyExtractor: TopologyFileExtractor,
    val indexRefresh: WorkspaceIndexRefreshOperations,
    val change: InstalledChangePhysicalPorts,
)

private data class InstalledRuntimePlatformPorts(
    val semantic: SemanticRuntimePorts,
    val topologyExtractor: TopologyFileExtractor,
    val indexRefresh: WorkspaceIndexRefreshOperations,
    val change: InstalledChangePhysicalPorts,
)

private fun interface InstalledRuntimePortFactory {
    fun create(
        workspace: WorkspacePublicationCoordinator,
        model: InstalledWorkspaceModelAdapter,
    ): InstalledRuntimePlatformPorts
}

/**
 * Proof transition: `InstalledKastRuntimeRequest -> InstalledRuntimeAssembly`.
 *
 * Establishes one production assembler that opens/imports the exact IntelliJ workspace, detaches
 * and refines its Gradle model, then constructs only exact-root compiler and change ports around
 * the resulting publication coordinator. Every bootstrap failure remains closed assembly data.
 */
internal fun productionInstalledRuntimeAssembler(): InstalledRuntimeAssembler =
    InstalledRuntimeAssembler { request ->
        val workspaceModel = when (val opened = InstalledIntellijWorkspace.open(
            request.workspaceRoot.path,
            InstalledIntellijWorkspaceBootstrapObserver { phase ->
                request.bootstrapObserver.observe(phase.runtimePhase())
            },
        )) {
            is InstalledIntellijWorkspaceOpening.Opened -> opened.model
            is InstalledIntellijWorkspaceOpening.Rejected -> return@InstalledRuntimeAssembler rejected(
                InstalledRuntimeWorkspaceFailure.IntellijBootstrap(opened.failure),
            )
        }
        val capture = workspaceModel.capture
        val read = projectInstalledGradleModel(
            InstalledGradleModelBoundary(
                capture.root,
                true,
                capture.sourceRoots,
                capture.identity,
            ),
        )
        val initial = when (read) {
            is InstalledGradleModelRead.Captured -> read
            is InstalledGradleModelRead.Unavailable -> return@InstalledRuntimeAssembler rejected(
                InstalledRuntimeWorkspaceFailure.ModelRefinementUnavailable(read.failure),
            )
        }
        publishInstalledRuntimeIndexScope(initial.model, request.bootstrapObserver)
        request.bootstrapObserver.observe(InstalledRuntimeBootstrapPhase.RUNTIME_ASSEMBLY)
        assembleInstalledRuntime(
            request,
            RefreshingInstalledGradleModelReads(initial, workspaceModel),
            { workspace, model ->
                productionPlatformPorts(
                    request,
                    workspace,
                    model,
                    workspaceModel.awaitIndexReadinessAfter(intellijSourceRootIndexRefresh()),
                )
            },
        )
    }

internal fun publishInstalledRuntimeIndexScope(
    model: InstalledGradleWorkspaceModel,
    observer: InstalledRuntimeBootstrapObserver,
) {
    observer.observeIndexScope(
        InstalledRuntimeIndexScope(model.root, model.sourceRoots),
    )
}

private fun InstalledIntellijWorkspaceBootstrapPhase.runtimePhase():
    InstalledRuntimeBootstrapPhase = when (this) {
    InstalledIntellijWorkspaceBootstrapPhase.PROJECT_IMPORT ->
        InstalledRuntimeBootstrapPhase.PROJECT_IMPORT
    InstalledIntellijWorkspaceBootstrapPhase.INDEXING -> InstalledRuntimeBootstrapPhase.INDEXING
    InstalledIntellijWorkspaceBootstrapPhase.MODEL_CAPTURE ->
        InstalledRuntimeBootstrapPhase.MODEL_CAPTURE
}

private class RefreshingInstalledGradleModelReads(
    initial: InstalledGradleModelRead.Captured,
    workspace: InstalledIntellijWorkspaceModel,
) : InstalledGradleModelReadOperations {
    private var state: State = State.Initial(initial, workspace)

    override fun read(): InstalledGradleModelRead = synchronized(this) {
        when (val current = state) {
            is State.Initial -> {
                state = State.Refreshing(current.workspace)
                current.read
            }
            is State.Refreshing -> current.workspace.currentModelRead()
        }
    }

    private sealed interface State {
        data class Initial(
            val read: InstalledGradleModelRead.Captured,
            val workspace: InstalledIntellijWorkspaceModel,
        ) : State

        data class Refreshing(
            val workspace: InstalledIntellijWorkspaceModel,
        ) : State
    }
}

private fun InstalledIntellijWorkspaceModel.currentModelRead(): InstalledGradleModelRead = when (
    val current = captureCurrentSemanticIdentity()
) {
    is Refinement.Refined -> projectInstalledGradleModel(
        InstalledGradleModelBoundary(
            capture.root,
            true,
            capture.sourceRoots,
            current.value,
        ),
    )
    is Refinement.Rejected -> InstalledGradleModelRead.Unavailable(
        io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelFailure
            .SemanticIdentityUnavailable(current.failure),
    )
}

/**
 * Proof transition: `InstalledRuntimeAssemblyInputs -> InstalledRuntimeAssembler`.
 *
 * Establishes one assembler that owns exact-root workspace reconciliation, both durable SQLite
 * authorities, every target service, and all canonical handlers. [InstalledRuntimeAssembly]
 * closes every expected bootstrap failure. Raw paths are extracted only while opening persistence
 * and the physical workspace adapter.
 */
internal fun productionInstalledRuntimeAssembler(
    inputs: InstalledRuntimeAssemblyInputs,
): InstalledRuntimeAssembler = InstalledRuntimeAssembler { request ->
    assembleInstalledRuntime(
        request,
        inputs.workspaceModel,
    ) { _, _ ->
        InstalledRuntimePlatformPorts(
            inputs.semantic,
            inputs.topologyExtractor,
            inputs.indexRefresh,
            inputs.change,
        )
    }
}

private fun assembleInstalledRuntime(
    request: InstalledKastRuntimeRequest,
    modelRead: InstalledGradleModelReadOperations,
    ports: InstalledRuntimePortFactory,
): InstalledRuntimeAssembly {
    val publication = when (val opened = SqliteWorkspacePublicationDatabase.open(
        request.stateDirectory.path.resolve("workspace-publication.sqlite"),
    )) {
        is SqliteWorkspacePublicationDatabaseOpening.Opened ->
            SqliteCanonicalWorkspacePublicationTransaction(opened.database)
        is SqliteWorkspacePublicationDatabaseOpening.Rejected -> return rejected(
            InstalledRuntimePersistenceFailure.WORKSPACE_PUBLICATION_UNAVAILABLE,
        )
    }
    val recovery = when (val opened = SqliteMutationRecoveryJournal.open(
        request.stateDirectory.path.resolve("mutation-recovery.sqlite"),
    )) {
        is SqliteMutationRecoveryJournalOpenResult.Opened -> opened.journal
        is SqliteMutationRecoveryJournalOpenResult.Rejected -> return rejected(
            InstalledRuntimePersistenceFailure.MUTATION_RECOVERY_UNAVAILABLE,
        )
    }
    val topologySnapshots = when (val opened = SqliteTopologySnapshotStore.open(
        request.stateDirectory.path.resolve("topology.sqlite"),
    )) {
        is SqliteTopologySnapshotStoreOpening.Opened -> opened.store
        is SqliteTopologySnapshotStoreOpening.Rejected -> return rejected(
            InstalledRuntimePersistenceFailure.TOPOLOGY_SNAPSHOT_UNAVAILABLE,
        )
    }
    val workspaceModel = InstalledWorkspaceModelAdapter(modelRead)
    val reconciliation = IntellijWorkspaceReconciliationPort(
        { request.workspaceRoot.path },
        workspaceModel,
        workspaceModel,
    )
    val workspace = WorkspacePublicationCoordinator(reconciliation, publication)
    val publishedWorkspace = when (val publicationRun = workspace.reconcile()) {
        is WorkspacePublicationRun.Published -> publicationRun.workspace
        is WorkspacePublicationRun.Unchanged -> publicationRun.workspace
        WorkspacePublicationRun.NoWork ->
            return rejected(InstalledRuntimeWorkspaceFailure.NoPublication)
        WorkspacePublicationRun.Invalidated ->
            return rejected(InstalledRuntimeWorkspaceFailure.Invalidated)
        is WorkspacePublicationRun.Blocked ->
            return rejected(InstalledRuntimeWorkspaceFailure.Blocked)
    }
    if (publishedWorkspace.root != request.workspaceRoot.canonicalRoot) {
        return rejected(InstalledRuntimeWorkspaceFailure.RootMismatch)
    }
    val platform = ports.create(workspace, workspaceModel)
    val graph = KastRuntimeComposition.constructGraph(
        workspace,
        platform.semantic,
        TopologyRuntimePorts(
            intellijSynchronizedTopologyCandidateEnumerator(),
            platform.topologyExtractor,
            topologySnapshots,
        ),
        IndexRuntimePorts(platform.indexRefresh, ForkJoinPool.commonPool()),
        ChangeRuntimePorts(
            recovery,
            platform.change.sourceObserver,
            platform.change.sourceWriter,
            platform.change.sourceRollback,
            platform.change.recoveryRollback,
            InstalledChangeVerificationObserver(
                workspace,
                platform.semantic,
                platform.change.sourceObserver,
            ),
        ),
        request.observability,
    )
    val handlers = when (val created = CanonicalKastOperationHandlerFactory.create(
        request.workspaceRoot,
        graph.operations.workspaceInspect,
        InstalledChangePlanningAdmission(
            graph.operations.workspaceInspect,
            graph.operations.symbolDescribe,
            graph.operations.relationRead,
            graph.operations.traversalRun,
            graph.operations.diagnosticCheck,
            platform.change.sourceObserver,
            platform.change.intentCompiler,
        ),
    )) {
        is Refinement.Refined -> created.value
        is Refinement.Rejected -> return InstalledRuntimeAssembly.Rejected(
            InstalledRuntimeAssemblyFailure.WorkspaceHandler(created.failure),
        )
    }
    return when (val composition = KastRuntimeComposition.bind(graph.operations, handlers)) {
        is KastRuntimeCompositionConstruction.Created ->
            InstalledRuntimeAssembly.Assembled(composition.composition)
        is KastRuntimeCompositionConstruction.Rejected -> InstalledRuntimeAssembly.Rejected(
            InstalledRuntimeAssemblyFailure.Composition(composition.failures),
        )
    }
}

private fun productionPlatformPorts(
    request: InstalledKastRuntimeRequest,
    workspace: WorkspacePublicationCoordinator,
    model: InstalledWorkspaceModelAdapter,
    indexRefresh: WorkspaceIndexRefreshOperations,
): InstalledRuntimePlatformPorts {
    val root = request.workspaceRoot.canonicalRoot
    val symbols = InstalledIntellijSymbolPorts.create(
        root,
        workspace,
        InstalledSymbolScopeOperations(model::searchScope),
    )
    val relation = installedIntellijRelationCompiler(
        root,
        workspace,
        InstalledRelationScopeOperations(model::searchScope),
    )
    val change = InstalledIntellijChangePorts.create(root)
    return InstalledRuntimePlatformPorts(
        SemanticRuntimePorts(
            symbols.discovery,
            symbols.exact,
            InstalledIntellijSourceReadPort.create(root),
            relation,
            installedIntellijDiagnosticCompiler(root, workspace),
        ),
        installedIntellijTopologyExtractor(root, workspace),
        indexRefresh,
        InstalledChangePhysicalPorts(
            change.sourceObserver,
            change.sourceWriter,
            change.sourceRollback,
            change.recoveryRollback,
            change.intentCompiler,
        ),
    )
}

private fun rejected(
    failure: InstalledRuntimePersistenceFailure,
): InstalledRuntimeAssembly.Rejected = InstalledRuntimeAssembly.Rejected(
    InstalledRuntimeAssemblyFailure.Persistence(failure),
)

private fun rejected(
    failure: InstalledRuntimeWorkspaceFailure,
): InstalledRuntimeAssembly.Rejected = InstalledRuntimeAssembly.Rejected(
    InstalledRuntimeAssemblyFailure.WorkspacePublication(failure),
)
