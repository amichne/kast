package io.github.amichne.kast.symbol.service

import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.ResolvedSymbol
import io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerRejection
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Current-generation admission owner for public `symbol.resolve` and `symbol.describe`. */
class SymbolExactService(
    private val workspaces: WorkspaceInspectionOperations,
    private val compiler: SymbolExactCompilerPort,
) : SymbolExactOperations {
    /**
     * Proof transition: `(WorkspaceRuntimeState, SymbolResolutionRequest,
     * SymbolResolutionCompilation) -> SymbolResolutionResult`.
     *
     * A resolved result establishes that the batch-owned selection remained current before and
     * after compiler resolution and that the returned selector retains its exact lease, scope,
     * file, name, and offset. [SymbolExactRejection] is the closed expected failure. Workspace
     * observation and compiler execution are the only effect boundaries.
     */
    override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult {
        when (
            val admission = admitCurrentLease(
                request.selection.lease,
                SymbolExactRejection.WORKSPACE_NOT_READY,
            )
        ) {
            SymbolExactLeaseAdmission.Admitted -> Unit
            is SymbolExactLeaseAdmission.Rejected ->
                return SymbolResolutionResult.Rejected(admission.reason)
        }
        val selector = when (val compilation = compiler.resolve(request)) {
            is SymbolResolutionCompilation.Resolved -> compilation.selector
            is SymbolResolutionCompilation.Rejected ->
                return SymbolResolutionResult.Rejected(compilation.reason.toPublicRejection())
        }
        when (
            val admission = admitCurrentLease(
                request.selection.lease,
                SymbolExactRejection.STALE_GENERATION,
            )
        ) {
            SymbolExactLeaseAdmission.Admitted -> Unit
            is SymbolExactLeaseAdmission.Rejected ->
                return SymbolResolutionResult.Rejected(admission.reason)
        }
        return when (admitSelector(request.selection, selector)) {
            SymbolSelectorAdmission.Admitted ->
                SymbolResolutionResult.Resolved(ResolvedSymbol(selector))
            SymbolSelectorAdmission.Rejected ->
                SymbolResolutionResult.Rejected(
                    SymbolExactRejection.COMPILER_CONTRACT_VIOLATION,
                )
        }
    }

    /**
     * Proof transition: `(WorkspaceRuntimeState, ExactSymbolRequest,
     * SymbolDescriptionCompilation) -> SymbolDescriptionResult`.
     *
     * A described result establishes that the selector remained current before and after native
     * compiler revalidation and that the detached description retains the same exact selector.
     * [SymbolExactRejection] is the closed expected failure. Workspace observation and compiler
     * execution are the only effect boundaries.
     */
    override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult {
        when (
            val admission = admitCurrentLease(
                request.selector.lease,
                SymbolExactRejection.WORKSPACE_NOT_READY,
            )
        ) {
            SymbolExactLeaseAdmission.Admitted -> Unit
            is SymbolExactLeaseAdmission.Rejected ->
                return SymbolDescriptionResult.Rejected(admission.reason)
        }
        val description = when (val compilation = compiler.describe(request)) {
            is SymbolDescriptionCompilation.Described -> compilation.description
            is SymbolDescriptionCompilation.Rejected ->
                return SymbolDescriptionResult.Rejected(compilation.reason.toPublicRejection())
        }
        when (
            val admission = admitCurrentLease(
                request.selector.lease,
                SymbolExactRejection.STALE_GENERATION,
            )
        ) {
            SymbolExactLeaseAdmission.Admitted -> Unit
            is SymbolExactLeaseAdmission.Rejected ->
                return SymbolDescriptionResult.Rejected(admission.reason)
        }
        return if (description.selector === request.selector) {
            SymbolDescriptionResult.Described(description)
        } else {
            SymbolDescriptionResult.Rejected(
                SymbolExactRejection.COMPILER_CONTRACT_VIOLATION,
            )
        }
    }

    /**
     * Proof transition: `(WorkspaceRuntimeState, SemanticReadLease, SymbolExactRejection) ->
     * SymbolExactLeaseAdmission`.
     *
     * [SymbolExactLeaseAdmission.Admitted] establishes that a ready publication has the exact
     * expected canonical root and generation. [SymbolExactLeaseAdmission.Rejected] preserves
     * unavailable, root-mismatch, or stale-generation failure as closed data. Raw root and
     * generation extraction remains inside workspace publication.
     */
    private fun admitCurrentLease(
        expected: SemanticReadLease,
        unavailable: SymbolExactRejection,
    ): SymbolExactLeaseAdmission {
        val current = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace.readLease
            WorkspaceRuntimeState.Absent,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Reconciling,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Stopping,
                -> return SymbolExactLeaseAdmission.Rejected(unavailable)
        }
        return when {
            expected.workspaceRoot != current.workspaceRoot ->
                SymbolExactLeaseAdmission.Rejected(
                    SymbolExactRejection.WORKSPACE_ROOT_MISMATCH,
                )
            expected.generation != current.generation ->
                SymbolExactLeaseAdmission.Rejected(SymbolExactRejection.STALE_GENERATION)
            else -> SymbolExactLeaseAdmission.Admitted
        }
    }
}

private sealed interface SymbolExactLeaseAdmission {
    data object Admitted : SymbolExactLeaseAdmission

    data class Rejected(
        val reason: SymbolExactRejection,
    ) : SymbolExactLeaseAdmission
}

private sealed interface SymbolSelectorAdmission {
    data object Admitted : SymbolSelectorAdmission

    data object Rejected : SymbolSelectorAdmission
}

/**
 * Proof transition: `(SymbolDiscoverySelection, SymbolSelector) -> SymbolSelectorAdmission`.
 *
 * Admission establishes exact preservation of the selected lease, scope, file, name, and source
 * offset. Rejection is closed and becomes a compiler-contract violation at the public boundary.
 */
private fun admitSelector(
    selection: SymbolDiscoverySelection,
    selector: SymbolSelector,
): SymbolSelectorAdmission {
    val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
    return if (
        selector.lease == selection.lease &&
        selector.scope == selection.scope &&
        selector.file == location.file &&
        selector.name == selection.candidate.name &&
        selector.range.startInclusive == location.offset.value
    ) {
        SymbolSelectorAdmission.Admitted
    } else {
        SymbolSelectorAdmission.Rejected
    }
}

private fun SymbolExactCompilerRejection.toPublicRejection(): SymbolExactRejection = when (this) {
    SymbolExactCompilerRejection.WORKSPACE_ROOT_MISMATCH ->
        SymbolExactRejection.WORKSPACE_ROOT_MISMATCH
    SymbolExactCompilerRejection.GENERATION_MOVED -> SymbolExactRejection.STALE_GENERATION
    SymbolExactCompilerRejection.SCOPE_REJECTED -> SymbolExactRejection.SCOPE_REJECTED
    SymbolExactCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE ->
        SymbolExactRejection.WORKSPACE_INDEX_UNAVAILABLE
    SymbolExactCompilerRejection.STALE_LOCATION -> SymbolExactRejection.STALE_LOCATION
    SymbolExactCompilerRejection.OUTSIDE_SCOPE -> SymbolExactRejection.OUTSIDE_SCOPE
    SymbolExactCompilerRejection.AMBIGUOUS_DECLARATION ->
        SymbolExactRejection.AMBIGUOUS_DECLARATION
    SymbolExactCompilerRejection.UNSUPPORTED_DECLARATION ->
        SymbolExactRejection.UNSUPPORTED_DECLARATION
    SymbolExactCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE ->
        SymbolExactRejection.COMPILER_IDENTITY_UNAVAILABLE
    SymbolExactCompilerRejection.DECLARATION_MOVED_OR_CHANGED ->
        SymbolExactRejection.DECLARATION_MOVED_OR_CHANGED
    SymbolExactCompilerRejection.INTERNAL_INVARIANT ->
        SymbolExactRejection.COMPILER_CONTRACT_VIOLATION
}
