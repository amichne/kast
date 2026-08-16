package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.evidence.sqlite.SqliteCanonicalWorkspacePublicationTransaction
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournal
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournalOpenResult
import io.github.amichne.kast.evidence.sqlite.SqliteWorkspacePublicationDatabase
import io.github.amichne.kast.evidence.sqlite.SqliteWorkspacePublicationDatabaseOpening
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelReadOperations
import io.github.amichne.kast.runtime.composition.platform.InstalledWorkspaceModelAdapter
import io.github.amichne.kast.runtime.composition.protocol.CanonicalKastOperationHandlerFactory
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmissionOperations
import io.github.amichne.kast.workspace.contract.WorkspacePublicationRun
import io.github.amichne.kast.workspace.intellij.IntellijWorkspaceReconciliationPort

/** Closed construction inputs whose live platform values remain behind narrow adapter ports. */
internal data class InstalledRuntimeAssemblyInputs(
    val workspaceModel: InstalledGradleModelReadOperations,
    val semantic: SemanticRuntimePorts,
    val change: InstalledChangePhysicalPorts,
    val changeAdmission: ChangePlanAdmissionOperations,
)

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
    assembleInstalledRuntime(request, inputs)
}

private fun assembleInstalledRuntime(
    request: InstalledKastRuntimeRequest,
    inputs: InstalledRuntimeAssemblyInputs,
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
    val workspaceModel = InstalledWorkspaceModelAdapter(inputs.workspaceModel)
    val reconciliation = IntellijWorkspaceReconciliationPort(
        { request.workspaceRoot.path },
        workspaceModel,
        workspaceModel,
    )
    val graph = KastRuntimeComposition.constructGraph(
        WorkspaceRuntimePorts(reconciliation, publication),
        inputs.semantic,
        ChangeRuntimePorts(
            recovery,
            inputs.change.sourceObserver,
            inputs.change.sourceWriter,
            inputs.change.sourceRollback,
            inputs.change.recoveryRollback,
            inputs.change.verificationObserver,
        ),
    )
    when (val publicationRun = graph.workspace.reconcile()) {
        is WorkspacePublicationRun.Published -> if (
            publicationRun.workspace.root != request.workspaceRoot.canonicalRoot
        ) {
            return rejected(InstalledRuntimeWorkspaceFailure.ROOT_MISMATCH)
        }
        WorkspacePublicationRun.NoWork ->
            return rejected(InstalledRuntimeWorkspaceFailure.NO_PUBLICATION)
        WorkspacePublicationRun.Invalidated ->
            return rejected(InstalledRuntimeWorkspaceFailure.INVALIDATED)
        is WorkspacePublicationRun.Blocked ->
            return rejected(InstalledRuntimeWorkspaceFailure.BLOCKED)
    }
    val handlers = when (val created = CanonicalKastOperationHandlerFactory.create(
        request.workspaceRoot,
        graph.operations.workspaceInspect,
        inputs.changeAdmission,
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
