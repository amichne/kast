package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationApplyOperations
import io.github.amichne.kast.change.verify.VerifiedMutationOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.runtime.server.TypedOperationBinding
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations

/**
 * Generated wire-binding boundary whose nominal methods preserve every target service association.
 *
 * Implementations project transport values to the supplied service contract and must not route
 * through an aggregate backend, compatibility adapter, fallback, or service locator.
 */
interface KastOperationBindingFactory {
    fun workspaceInspect(
        operations: WorkspaceInspectionOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun symbolDiscover(
        operations: SymbolDiscoveryOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun symbolResolve(
        operations: SymbolExactOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun symbolDescribe(
        operations: SymbolExactOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun relationRead(
        operations: RelationOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun traversalRun(
        operations: TraversalOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun diagnosticCheck(
        operations: DiagnosticOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun changePlan(
        operations: ChangePlanningOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun changeApply(
        operations: AddDeclarationApplyOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun changeVerify(
        operations: VerifiedMutationOperations,
    ): TypedOperationBinding<*, *, *, *>

    fun changeRecover(
        operations: ChangeRecoveryOperations,
    ): TypedOperationBinding<*, *, *, *>
}
