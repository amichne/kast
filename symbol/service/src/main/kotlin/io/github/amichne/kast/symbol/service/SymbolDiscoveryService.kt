package io.github.amichne.kast.symbol.service

import io.github.amichne.kast.symbol.contract.SymbolCompilation
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Current-generation admission owner for the public `symbol.discover` operation. */
class SymbolDiscoveryService(
    private val workspaces: WorkspaceInspectionOperations,
    private val compiler: SymbolCompilerPort,
) : SymbolDiscoveryOperations {
    /**
     * Proof transition: `(WorkspaceRuntimeState, SymbolDiscoveryRequest, SymbolCompilation) ->
     * SymbolDiscoveryResult`.
     *
     * A discovered result establishes that only the current published lease reached compiler
     * work and that the returned scope, lease, candidate kind, and measured work remain within the
     * request. [SymbolDiscoveryRejection] is the closed expected failure. Workspace observation
     * and compiler execution are the only outer effect boundaries.
     */
    override suspend fun discover(request: SymbolDiscoveryRequest): SymbolDiscoveryResult {
        val workspace = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Reconciling,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Stopping,
                -> return SymbolDiscoveryResult.Rejected(
                SymbolDiscoveryRejection.WORKSPACE_NOT_READY,
            )
        }
        if (workspace.readLease != request.scope.lease) {
            return SymbolDiscoveryResult.Rejected(
                SymbolDiscoveryRejection.STALE_GENERATION,
            )
        }
        return when (val compilation = compiler.compile(request)) {
            is SymbolCompilation.Rejected -> SymbolDiscoveryResult.Rejected(
                when (compilation.reason) {
                    SymbolCompilerRejection.SCOPE_REJECTED ->
                        SymbolDiscoveryRejection.SCOPE_REJECTED
                    SymbolCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE ->
                        SymbolDiscoveryRejection.WORKSPACE_INDEX_UNAVAILABLE
                    SymbolCompilerRejection.PROVIDER_UNAVAILABLE ->
                        SymbolDiscoveryRejection.PROVIDER_UNAVAILABLE
                    SymbolCompilerRejection.INTERNAL_INVARIANT ->
                        SymbolDiscoveryRejection.COMPILER_CONTRACT_VIOLATION
                },
            )
            is SymbolCompilation.Compiled -> admitCompilation(request, compilation.outcome)
        }
    }

    /**
     * Proof transition: `(SymbolDiscoveryRequest, SymbolDiscoveryOutcome,
     * WorkspaceRuntimeState) -> SymbolDiscoveryResult`.
     *
     * A discovered result establishes that readiness remained current through compilation and
     * that lease, scope, kind, and work measures match the request. [SymbolDiscoveryRejection] is
     * the closed expected failure. The final workspace observation is the only effect boundary.
     */
    private fun admitCompilation(
        request: SymbolDiscoveryRequest,
        outcome: SymbolDiscoveryOutcome,
    ): SymbolDiscoveryResult {
        val currentLease = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace.readLease
            WorkspaceRuntimeState.Absent,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Reconciling,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Stopping,
                -> return SymbolDiscoveryResult.Rejected(
                SymbolDiscoveryRejection.STALE_GENERATION,
            )
        }
        val batch = when (outcome) {
            is SymbolDiscoveryOutcome.Complete -> outcome.batch
            is SymbolDiscoveryOutcome.Qualified -> outcome.batch
        }
        val contractHolds =
            currentLease == request.scope.lease &&
            batch.lease == request.scope.lease &&
            batch.scope == request.scope.scope &&
            batch.examinedWorkUnits.value <= request.budget.resources.workUnitLimit.value &&
            batch.candidates.all { candidate -> candidate.kind == request.kind }
        return if (contractHolds) {
            SymbolDiscoveryResult.Discovered(outcome)
        } else {
            SymbolDiscoveryResult.Rejected(
                SymbolDiscoveryRejection.COMPILER_CONTRACT_VIOLATION,
            )
        }
    }
}
