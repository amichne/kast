package io.github.amichne.kast.relation.service

import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Current-generation admission owner for public `relation.read`. */
class RelationService(
    private val workspaces: WorkspaceInspectionOperations,
    private val compiler: RelationCompilerPort,
) : RelationOperations {
    /**
     * Proof transition: `(WorkspaceRuntimeState, RelationRequest, RelationCompilation) ->
     * RelationReadResult`.
     *
     * A complete or qualified result establishes that the exact selector remained current before
     * and after compiler work and the detached output retained request ownership, meaning,
     * generation, exact fact coverage, and continuation binding. [RelationReadRejection] is the
     * closed expected failure. Workspace observation and compiler execution are the only effects.
     */
    override suspend fun read(request: RelationRequest): RelationReadResult {
        when (
            val admission = admitCurrentLease(
                request.selector.lease,
                RelationAdmissionPhase.INITIAL,
            )
        ) {
            RelationLeaseAdmission.Admitted -> Unit
            is RelationLeaseAdmission.Rejected ->
                return RelationReadResult.Rejected(admission.reason)
        }
        val compilation = compiler.read(request)
        when (
            val admission = admitCurrentLease(
                request.selector.lease,
                RelationAdmissionPhase.REVALIDATION,
            )
        ) {
            RelationLeaseAdmission.Admitted -> Unit
            is RelationLeaseAdmission.Rejected ->
                return RelationReadResult.Rejected(admission.reason)
        }
        return when (compilation) {
            is RelationCompilation.Complete -> {
                when (compilation.admitFor(request)) {
                    RelationCompilerOutputAdmission.Admitted ->
                        RelationReadResult.Complete(compilation.batch, compilation.coverage)
                    RelationCompilerOutputAdmission.Rejected -> contractRejected()
                }
            }
            is RelationCompilation.Qualified -> {
                when (compilation.admitFor(request)) {
                    RelationCompilerOutputAdmission.Admitted ->
                        RelationReadResult.Qualified(compilation.batch, compilation.coverage)
                    RelationCompilerOutputAdmission.Rejected -> contractRejected()
                }
            }
            is RelationCompilation.Rejected ->
                RelationReadResult.Rejected(compilation.reason.toPublicRejection())
        }
    }

    /**
     * Proof transition: `(WorkspaceRuntimeState, SemanticReadLease, RelationAdmissionPhase) ->
     * RelationLeaseAdmission`.
     *
     * [RelationLeaseAdmission.Admitted] establishes the exact ready root and generation.
     * [RelationLeaseAdmission.Rejected] preserves unavailable, root-mismatch, and stale-generation
     * states as [RelationReadRejection]. Raw state extraction remains at workspace publication.
     */
    private fun admitCurrentLease(
        expected: SemanticReadLease,
        phase: RelationAdmissionPhase,
    ): RelationLeaseAdmission {
        val current = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace.readLease
            WorkspaceRuntimeState.Absent,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Reconciling,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Stopping,
                -> return RelationLeaseAdmission.Rejected(
                    when (phase) {
                        RelationAdmissionPhase.INITIAL ->
                            RelationReadRejection.WORKSPACE_NOT_READY
                        RelationAdmissionPhase.REVALIDATION ->
                            RelationReadRejection.STALE_GENERATION
                    },
                )
        }
        return when {
            expected.workspaceRoot != current.workspaceRoot ->
                RelationLeaseAdmission.Rejected(RelationReadRejection.WORKSPACE_ROOT_MISMATCH)
            expected.generation != current.generation ->
                RelationLeaseAdmission.Rejected(RelationReadRejection.STALE_GENERATION)
            else -> RelationLeaseAdmission.Admitted
        }
    }
}

private enum class RelationAdmissionPhase {
    INITIAL,
    REVALIDATION,
}

private sealed interface RelationLeaseAdmission {
    data object Admitted : RelationLeaseAdmission

    data class Rejected(
        val reason: RelationReadRejection,
    ) : RelationLeaseAdmission
}

private enum class RelationCompilerOutputAdmission {
    Admitted,
    Rejected,
}

/**
 * Proof transition: `(RelationCompilation.Complete, RelationRequest) ->
 * RelationCompilerOutputAdmission`.
 *
 * Admitted proves exact request identity, count, and fact ownership. Rejected is the closed
 * compiler-contract failure consumed at the public service boundary.
 */
private fun RelationCompilation.Complete.admitFor(
    request: RelationRequest,
): RelationCompilerOutputAdmission {
    if (batch.request !== request || coverage.exactCount.value != batch.facts.size) {
        return RelationCompilerOutputAdmission.Rejected
    }
    return batch.facts.admitFor(request.selector, request)
}

/**
 * Proof transition: `(RelationCompilation.Qualified, RelationRequest) ->
 * RelationCompilerOutputAdmission`.
 *
 * Admitted proves request identity, known-minimum count, non-empty limitations, continuation
 * binding, and fact ownership. Rejected is the closed compiler-contract failure.
 */
private fun RelationCompilation.Qualified.admitFor(
    request: RelationRequest,
): RelationCompilerOutputAdmission {
    if (
        batch.request !== request ||
        coverage.knownMinimum.value != batch.facts.size ||
        coverage.limitations.isEmpty() ||
        coverage.continuation.selector != request.selector.fingerprint ||
        coverage.continuation.meaning != request.meaning ||
        coverage.continuation.generation != request.selector.lease.generation ||
        coverage.continuation.nextWorkOffset.value < request.position.workOffset.value
    ) {
        return RelationCompilerOutputAdmission.Rejected
    }
    return batch.facts.admitFor(request.selector, request)
}

private fun List<io.github.amichne.kast.relation.contract.RelationFact>.admitFor(
    selector: SymbolSelector,
    request: RelationRequest,
): RelationCompilerOutputAdmission = if (
    all { fact ->
        fact.subject === selector &&
            fact.meaning == request.meaning &&
            fact.generation == selector.lease.generation &&
            fact.source.lease == selector.lease &&
            fact.target.lease == selector.lease &&
            fact.source.scope == selector.scope &&
            fact.target.scope == selector.scope
    }
) {
    RelationCompilerOutputAdmission.Admitted
} else {
    RelationCompilerOutputAdmission.Rejected
}

private fun contractRejected(): RelationReadResult.Rejected = RelationReadResult.Rejected(
    RelationReadRejection.COMPILER_CONTRACT_VIOLATION,
)

private fun RelationCompilerRejection.toPublicRejection(): RelationReadRejection = when (this) {
    RelationCompilerRejection.WORKSPACE_ROOT_MISMATCH ->
        RelationReadRejection.WORKSPACE_ROOT_MISMATCH
    RelationCompilerRejection.GENERATION_MOVED -> RelationReadRejection.STALE_GENERATION
    RelationCompilerRejection.SCOPE_REJECTED -> RelationReadRejection.SCOPE_REJECTED
    RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE ->
        RelationReadRejection.WORKSPACE_INDEX_UNAVAILABLE
    RelationCompilerRejection.STALE_SELECTOR -> RelationReadRejection.STALE_SELECTOR
    RelationCompilerRejection.OUTSIDE_SCOPE -> RelationReadRejection.OUTSIDE_SCOPE
    RelationCompilerRejection.AMBIGUOUS_SUBJECT -> RelationReadRejection.AMBIGUOUS_SUBJECT
    RelationCompilerRejection.UNSUPPORTED_SUBJECT -> RelationReadRejection.UNSUPPORTED_SUBJECT
    RelationCompilerRejection.COMPILER_IDENTITY_UNAVAILABLE ->
        RelationReadRejection.COMPILER_IDENTITY_UNAVAILABLE
    RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION ->
        RelationReadRejection.COMPILER_CONTRACT_VIOLATION
}
