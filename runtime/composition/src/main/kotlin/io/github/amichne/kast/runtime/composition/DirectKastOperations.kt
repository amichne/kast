package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationApplyOperations
import io.github.amichne.kast.change.contract.AddDeclarationPlanOperations
import io.github.amichne.kast.change.contract.AddFilePlanOperations
import io.github.amichne.kast.change.contract.RenameSymbolPlanOperations
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanOperations
import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.change.plan.PureAddFilePlanningService
import io.github.amichne.kast.change.plan.PureRenameSymbolPlanningService
import io.github.amichne.kast.change.plan.PureReplaceDeclarationPlanningService
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryOutcome
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackPort
import io.github.amichne.kast.change.verify.VerifiedMutationOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations

/** The four closed intent planners consumed by the single public `change.plan` operation. */
class ChangePlanningOperations internal constructor(
    val addFile: AddFilePlanOperations,
    val addDeclaration: AddDeclarationPlanOperations,
    val replaceDeclaration: ReplaceDeclarationPlanOperations,
    val renameSymbol: RenameSymbolPlanOperations,
)

/** Direct operation boundary for durable recovery of one admitted mutation binding. */
fun interface ChangeRecoveryOperations {
    /**
     * Proof transition: `MutationPlanBinding -> AddDeclarationRecoveryOutcome`.
     *
     * Resolves the exact durable record to prior state, rolled back, or recovery required. The
     * injected rollback capability is the only outer source-effect boundary.
     */
    fun recover(binding: MutationPlanBinding): AddDeclarationRecoveryOutcome
}

/** Exact nominal target service association for the eleven public operations. */
@ConsistentCopyVisibility
data class DirectKastOperations internal constructor(
    val workspaceInspect: WorkspaceInspectionOperations,
    val symbolDiscover: SymbolDiscoveryOperations,
    val symbolResolve: SymbolExactOperations,
    val symbolDescribe: SymbolExactOperations,
    val relationRead: RelationOperations,
    val traversalRun: TraversalOperations,
    val diagnosticCheck: DiagnosticOperations,
    val changePlan: ChangePlanningOperations,
    val changeApply: AddDeclarationApplyOperations,
    val changeVerify: VerifiedMutationOperations,
    val changeRecover: ChangeRecoveryOperations,
) {
    companion object {
        /**
         * Proof transition: `(WorkspaceInspectionOperations, SymbolDiscoveryOperations,
         * SymbolExactOperations, RelationOperations, TraversalOperations, DiagnosticOperations,
         * AddDeclarationApplyOperations, VerifiedMutationOperations,
         * AddDeclarationRecoveryService, AddDeclarationRollbackPort) -> DirectKastOperations`.
         *
         * Establishes exactly one nominal target service association for every canonical
         * operation. The four closed change intents share one pure planning boundary, and resolve
         * and describe share one exact-symbol authority without an aggregate backend.
         */
        fun assemble(
            workspace: WorkspaceInspectionOperations,
            symbolDiscovery: SymbolDiscoveryOperations,
            symbolExact: SymbolExactOperations,
            relation: RelationOperations,
            traversal: TraversalOperations,
            diagnostic: DiagnosticOperations,
            changeApply: AddDeclarationApplyOperations,
            changeVerify: VerifiedMutationOperations,
            changeRecovery: AddDeclarationRecoveryService,
            changeRollback: AddDeclarationRollbackPort,
        ): DirectKastOperations = DirectKastOperations(
            workspaceInspect = workspace,
            symbolDiscover = symbolDiscovery,
            symbolResolve = symbolExact,
            symbolDescribe = symbolExact,
            relationRead = relation,
            traversalRun = traversal,
            diagnosticCheck = diagnostic,
            changePlan = ChangePlanningOperations(
                addFile = PureAddFilePlanningService(),
                addDeclaration = PureAddDeclarationPlanningService(),
                replaceDeclaration = PureReplaceDeclarationPlanningService(),
                renameSymbol = PureRenameSymbolPlanningService(),
            ),
            changeApply = changeApply,
            changeVerify = changeVerify,
            changeRecover = { binding ->
                changeRecovery.recover(binding, changeRollback)
            },
        )
    }
}
