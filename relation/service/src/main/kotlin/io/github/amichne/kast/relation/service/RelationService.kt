package io.github.amichne.kast.relation.service

import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanCount
import io.github.amichne.kast.kernel.KastSpanFailure
import io.github.amichne.kast.kernel.KastSpanMeasurement
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Current-generation admission owner for public `relation.read`. */
class RelationService(
    private val workspaces: WorkspaceInspectionOperations,
    private val compiler: RelationCompilerPort,
    private val observability: KastObservability = KastObservability.Disabled,
) : RelationOperations {
    /**
     * Proof transition: `(WorkspaceRuntimeState, RelationRequest, RelationCompilation) ->
     * RelationReadResult`.
     *
     * A complete or qualified result establishes that the exact subject remained current before
     * and after compiler work and the detached output retained request ownership, meaning,
     * generation, exact fact coverage, and continuation binding. [RelationReadRejection] is the
     * closed expected failure. Workspace observation and compiler execution are the only effects.
     */
    override suspend fun read(request: RelationRequest): RelationReadResult =
        observability.inSpan(KastSpanName.RELATION_READ) { span ->
            readObserved(request).also { result -> span.observe(result.traceObservation()) }
        }

    private suspend fun readObserved(request: RelationRequest): RelationReadResult {
        when (
            val admission = admitCurrentLease(
                request.subject.lease,
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
                request.subject.lease,
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

private fun RelationReadResult.traceObservation(): KastSpanObservation = when (this) {
    is RelationReadResult.Complete -> batch.completeObservation()
    is RelationReadResult.Qualified -> batch.qualifiedObservation()
    is RelationReadResult.Rejected -> KastSpanObservation(
        KastSpanCompletion.Rejected(
            when (reason) {
                RelationReadRejection.WORKSPACE_NOT_READY ->
                    KastSpanFailure.RELATION_WORKSPACE_NOT_READY
                RelationReadRejection.WORKSPACE_ROOT_MISMATCH,
                RelationReadRejection.STALE_GENERATION,
                    -> KastSpanFailure.RELATION_WORKSPACE_MOVED
                RelationReadRejection.SCOPE_REJECTED,
                RelationReadRejection.WORKSPACE_INDEX_UNAVAILABLE,
                RelationReadRejection.STALE_SELECTOR,
                RelationReadRejection.OUTSIDE_SCOPE,
                RelationReadRejection.AMBIGUOUS_SUBJECT,
                RelationReadRejection.UNSUPPORTED_SUBJECT,
                RelationReadRejection.COMPILER_IDENTITY_UNAVAILABLE,
                RelationReadRejection.CONTINUATION_CURSOR_MOVED,
                RelationReadRejection.COMPILER_CONTRACT_VIOLATION,
                    -> KastSpanFailure.RELATION_QUERY_REJECTED
            },
        ),
    )
}

private fun RelationBatch.completeObservation(): KastSpanObservation =
    KastSpanObservation(KastSpanCompletion.Complete, measurements())

private fun RelationBatch.qualifiedObservation(): KastSpanObservation =
    KastSpanObservation(KastSpanCompletion.Qualified, measurements())

private fun RelationBatch.measurements(): Set<KastSpanMeasurement> = setOf(
    KastSpanMeasurement.RecordCount(exactSpanCount(facts.size.toLong())),
    KastSpanMeasurement.WorkUnitCount(exactSpanCount(examinedWorkUnits.value)),
)

private fun exactSpanCount(raw: Long): KastSpanCount = when (val parsed = KastSpanCount.parse(raw)) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("A proven relation measurement cannot be negative")
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
    if (
        batch.request !== request ||
        coverage.exactCount.value != batch.facts.size ||
        batch.resultCount.value != batch.facts.size
    ) {
        return RelationCompilerOutputAdmission.Rejected
    }
    return batch.facts.admitFor(request.subject, request)
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
        batch.resultCount.value != batch.facts.size ||
        coverage.limitations.isEmpty()
    ) {
        return RelationCompilerOutputAdmission.Rejected
    }
    val admittedCoverage = coverage
    if (
        admittedCoverage is
        io.github.amichne.kast.relation.contract.RelationIncompleteCoverage.Resumable
    ) {
        val continuation = admittedCoverage.continuation
        if (
            continuation.subject != request.subject.fingerprint ||
            continuation.meaning != request.meaning ||
            continuation.generation != request.subject.lease.generation ||
            continuation.nextProviderCursor.provider != request.providerCursor.provider ||
            continuation.nextProviderCursor.nextPosition.value <
            request.providerCursor.nextPosition.value
        ) {
            return RelationCompilerOutputAdmission.Rejected
        }
    }
    return batch.facts.admitFor(request.subject, request)
}

private fun List<io.github.amichne.kast.relation.contract.RelationFact>.admitFor(
    subject: RelationEndpoint,
    request: RelationRequest,
): RelationCompilerOutputAdmission = if (
    all { fact ->
        fact.subject === subject &&
        fact.meaning == request.meaning &&
        fact.generation == subject.lease.generation &&
        fact.source.lease == subject.lease &&
        fact.target.lease == subject.lease &&
        fact.source.scope == subject.scope &&
        fact.target.scope == subject.scope
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
    RelationCompilerRejection.CONTINUATION_CURSOR_MOVED ->
        RelationReadRejection.CONTINUATION_CURSOR_MOVED
    RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION ->
        RelationReadRejection.COMPILER_CONTRACT_VIOLATION
}
