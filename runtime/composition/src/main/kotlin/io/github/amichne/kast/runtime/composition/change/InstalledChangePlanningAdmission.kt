package io.github.amichne.kast.runtime.composition.change

import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.EditableMutationTarget
import io.github.amichne.kast.change.contract.MutationTargetObservation
import io.github.amichne.kast.change.contract.ObservedMutationTargetState
import io.github.amichne.kast.change.intellij.InstalledAddDeclarationIntentCompilation
import io.github.amichne.kast.change.intellij.InstalledAddDeclarationIntentCompiler
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.runtime.composition.installedSemanticBudgets
import io.github.amichne.kast.runtime.composition.protocol.AuthorizedChangeIntent
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmission
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmissionFailure
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmissionOperations
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.nio.file.Path

/** Exact-root semantic and physical refinement used before pure installed change planning. */
internal class InstalledChangePlanningAdmission(
    private val workspace: WorkspaceInspectionOperations,
    private val symbols: SymbolExactOperations,
    private val relations: RelationOperations,
    private val traversals: TraversalOperations,
    private val diagnostics: DiagnosticOperations,
    private val sources: AddDeclarationSourceObserver,
    private val intents: InstalledAddDeclarationIntentCompiler,
) : ChangePlanAdmissionOperations {
    /**
     * Proof transition: `AuthorizedChangeIntent -> ChangePlanAdmission`.
     *
     * AddDeclaration establishes current selector revalidation, exact authored source ownership,
     * byte-exact preimage, compiler-refined declaration identity, and complete relation, traversal,
     * and diagnostic evidence. Every unavailable or incomplete state remains a closed rejection;
     * raw declaration text leaves only for the installed IntelliJ intent compiler.
     */
    override suspend fun admit(intent: AuthorizedChangeIntent): ChangePlanAdmission = when (intent) {
        is AuthorizedChangeIntent.AddDeclaration -> admitAddDeclaration(intent)
        is AuthorizedChangeIntent.AddFile,
        is AuthorizedChangeIntent.RenameSymbol,
        is AuthorizedChangeIntent.ReplaceDeclaration,
            -> rejected(ChangePlanAdmissionFailure.INTENT_REJECTED)
    }

    private suspend fun admitAddDeclaration(
        intent: AuthorizedChangeIntent.AddDeclaration,
    ): ChangePlanAdmission {
        val published = (workspace.inspect() as? WorkspaceRuntimeState.Ready)?.workspace
                        ?: return rejected(ChangePlanAdmissionFailure.WORKSPACE_NOT_READY)
        if (intent.selector.lease != published.readLease) {
            return rejected(ChangePlanAdmissionFailure.SYMBOL_RESOLVE_REQUIRED)
        }
        val selector = when (val described = symbols.describe(ExactSymbolRequest(intent.selector))) {
            is SymbolDescriptionResult.Described -> described.description.selector
            is SymbolDescriptionResult.Rejected -> return rejected(
                ChangePlanAdmissionFailure.SYMBOL_RESOLVE_REQUIRED,
            )
        }
        val file = selector.file as? SymbolDiscoveryFileIdentity.Workspace
                   ?: return rejected(ChangePlanAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        val observed = when (val result = sources.observe(file)) {
            is SourceObservationResult.Observed -> result.source as? ObservedMutationSource
                                                   ?: return rejected(ChangePlanAdmissionFailure.EDITABLE_TARGET_REQUIRED)
            is SourceObservationResult.Rejected -> return rejected(
                ChangePlanAdmissionFailure.EDITABLE_TARGET_REQUIRED,
            )
        }
        if (observed.access != SourceWriteAccess.Writable) {
            return rejected(ChangePlanAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        }
        val owner = published.sourceRoots.singleOrNull { sourceRoot ->
            val sourceRootPath = Path.of(published.root.value)
                .resolve(sourceRoot.location.value)
                .normalize()
            val targetPath = Path.of(file.path.value)
            targetPath != sourceRootPath && targetPath.startsWith(sourceRootPath)
        }?.owner ?: return rejected(ChangePlanAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        val target = when (val admitted = EditableMutationTarget.admit(
            MutationTargetObservation(
                workspace = published,
                selector = selector,
                expectedOwner = owner,
                observedState = ObservedMutationTargetState(
                    published.readLease,
                    file,
                    observed.content,
                ),
            ),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(ChangePlanAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        }
        val compiled = when (val result = intents.compile(selector, intent.declaration.value)) {
            is InstalledAddDeclarationIntentCompilation.Compiled -> result.intent
            is InstalledAddDeclarationIntentCompilation.Rejected -> return rejected(
                ChangePlanAdmissionFailure.INTENT_REJECTED,
            )
        }
        val budgets = installedSemanticBudgets()
            ?: return rejected(ChangePlanAdmissionFailure.RELATION_READ_REQUIRED)
        val relation = relations.read(
            RelationRequest.start(selector, RelationMeaning.References, budgets.relation),
        )
        if (relation !is RelationReadResult.Complete) {
            return rejected(ChangePlanAdmissionFailure.RELATION_READ_REQUIRED)
        }
        val traversalPlan = when (val admitted = TraversalPlan.start(
            selector,
            RelationMeaning.References,
            budgets.traversal,
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                ChangePlanAdmissionFailure.INTENT_REJECTED,
            )
        }
        val traversal = when (
            val required = traversals.run(traversalPlan).requireCompleteChangePlanTraversal()
        ) {
            is Refinement.Refined -> required.value
            is Refinement.Rejected -> return rejected(required.failure)
        }
        val diagnosticScope = when (val admitted = DiagnosticScope.fromCanonicalPaths(
            published.readLease,
            listOf(Path.of(file.path.value)),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(ChangePlanAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        }
        val diagnostic = diagnostics.check(DiagnosticCheckRequest(diagnosticScope))
        if (diagnostic !is DiagnosticCheckResult.Complete) {
            return rejected(ChangePlanAdmissionFailure.DIAGNOSTIC_CHECK_REQUIRED)
        }
        return ChangePlanAdmission.AddDeclaration(
            AddDeclarationPlanRequest(
                target,
                compiled.declaration,
                compiled.expectedDelta,
                AddDeclarationPlanningEvidenceInput(
                    relations = listOf(relation),
                    traversals = listOf(traversal),
                    diagnostics = listOf(diagnostic),
                ),
            ),
        )
    }
}

/**
 * Proof transition: `TraversalResult ->
 * Refinement<TraversalResult.Complete, ChangePlanAdmissionFailure>`.
 *
 * Establishes that required change-planning traversal evidence is complete. Qualified evidence
 * remains the closed [ChangePlanAdmissionFailure.REQUIRED_TRAVERSAL_INCOMPLETE] failure, while
 * every traversal rejection retains its exact admission failure. The complete result may be
 * unpacked only while constructing planning evidence at the installed change-planning boundary.
 */
internal fun TraversalResult.requireCompleteChangePlanTraversal(): Refinement<
    TraversalResult.Complete,
    ChangePlanAdmissionFailure,
    > = when (this) {
    is TraversalResult.Complete -> Refinement.Refined(this)
    is TraversalResult.Qualified -> Refinement.Rejected(
        ChangePlanAdmissionFailure.REQUIRED_TRAVERSAL_INCOMPLETE,
    )
    is TraversalResult.Rejected -> Refinement.Rejected(reason.admissionFailure())
}

private fun TraversalRejection.admissionFailure(): ChangePlanAdmissionFailure = when (this) {
    TraversalRejection.RequiredEvidenceUnavailable,
    TraversalRejection.RequiredEvidenceStale,
        -> ChangePlanAdmissionFailure.TOPOLOGY_BUILD_REQUIRED
    is TraversalRejection.OneHopRejected -> when (reason) {
        io.github.amichne.kast.relation.contract.RelationReadRejection.WORKSPACE_NOT_READY ->
            ChangePlanAdmissionFailure.WORKSPACE_NOT_READY
        io.github.amichne.kast.relation.contract.RelationReadRejection.WORKSPACE_ROOT_MISMATCH,
        io.github.amichne.kast.relation.contract.RelationReadRejection.STALE_GENERATION,
        io.github.amichne.kast.relation.contract.RelationReadRejection.STALE_SELECTOR,
            -> ChangePlanAdmissionFailure.SYMBOL_RESOLVE_REQUIRED
        else -> ChangePlanAdmissionFailure.INTENT_REJECTED
    }
    TraversalRejection.ReaderContractViolation,
    TraversalRejection.TraversalContractViolation,
        -> ChangePlanAdmissionFailure.INTENT_REJECTED
}

private fun rejected(failure: ChangePlanAdmissionFailure): ChangePlanAdmission.Rejected =
    ChangePlanAdmission.Rejected(failure)
