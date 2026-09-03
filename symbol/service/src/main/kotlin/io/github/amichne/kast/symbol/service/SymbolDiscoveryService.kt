package io.github.amichne.kast.symbol.service

import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanCount
import io.github.amichne.kast.kernel.KastSpanFailure
import io.github.amichne.kast.kernel.KastSpanMeasurement
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolCompilation
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Current-generation admission owner for the public `symbol.discover` operation. */
class SymbolDiscoveryService(
    private val workspaces: WorkspaceInspectionOperations,
    private val compiler: SymbolCompilerPort,
    private val observability: KastObservability = KastObservability.Disabled,
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
    override suspend fun discover(request: SymbolDiscoveryRequest): SymbolDiscoveryResult =
        observability.inSpan(KastSpanName.SYMBOL_DISCOVERY) { span ->
            discoverObserved(request).also { result -> span.observe(result.traceObservation()) }
        }

    private suspend fun discoverObserved(request: SymbolDiscoveryRequest): SymbolDiscoveryResult {
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
            batch.candidates.all { candidate -> request.target.admits(candidate.kind) }
        return if (contractHolds) {
            SymbolDiscoveryResult.Discovered(outcome)
        } else {
            SymbolDiscoveryResult.Rejected(
                SymbolDiscoveryRejection.COMPILER_CONTRACT_VIOLATION,
            )
        }
    }
}

private fun SymbolDiscoveryResult.traceObservation(): KastSpanObservation = when (this) {
    is SymbolDiscoveryResult.Discovered -> when (val discovered = outcome) {
        is SymbolDiscoveryOutcome.Complete -> discovered.batch.completeObservation()
        is SymbolDiscoveryOutcome.Qualified -> discovered.batch.qualifiedObservation()
    }
    is SymbolDiscoveryResult.Rejected -> KastSpanObservation(
        KastSpanCompletion.Rejected(
            when (reason) {
                SymbolDiscoveryRejection.WORKSPACE_NOT_READY ->
                    KastSpanFailure.SYMBOL_WORKSPACE_NOT_READY
                SymbolDiscoveryRejection.STALE_GENERATION ->
                    KastSpanFailure.SYMBOL_STALE_GENERATION
                SymbolDiscoveryRejection.SCOPE_REJECTED,
                SymbolDiscoveryRejection.WORKSPACE_INDEX_UNAVAILABLE,
                SymbolDiscoveryRejection.PROVIDER_UNAVAILABLE,
                SymbolDiscoveryRejection.COMPILER_CONTRACT_VIOLATION,
                    -> KastSpanFailure.SYMBOL_QUERY_REJECTED
            },
        ),
    )
}

private fun SymbolDiscoveryBatch.completeObservation(): KastSpanObservation =
    KastSpanObservation(KastSpanCompletion.Complete, measurements())

private fun SymbolDiscoveryBatch.qualifiedObservation(): KastSpanObservation =
    KastSpanObservation(KastSpanCompletion.Qualified, measurements())

private fun SymbolDiscoveryBatch.measurements(): Set<KastSpanMeasurement> = setOf(
    KastSpanMeasurement.RecordCount(exactSpanCount(candidates.size.toLong())),
    KastSpanMeasurement.WorkUnitCount(exactSpanCount(examinedWorkUnits.value)),
)

private fun exactSpanCount(raw: Long): KastSpanCount = when (val parsed = KastSpanCount.parse(raw)) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("A proven discovery measurement cannot be negative")
}
