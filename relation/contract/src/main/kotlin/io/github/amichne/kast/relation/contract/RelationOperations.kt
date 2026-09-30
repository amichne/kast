package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

enum class RelationLimitation {
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DUMB_MODE_TRANSITION,
    UNRESOLVED_TARGET,
    UNSUPPORTED_ITEM,
    PROVIDER_FAILURE,
    PROVIDER_INCOMPLETE,
    PROVIDER_STALLED,
    CANDIDATE_LIMIT_REACHED,
    RETENTION_LIMIT_REACHED,
    PARTITION_INVENTORY_UNAVAILABLE,
}

@JvmInline value class RelationExactCount internal constructor(val value: Int)

@JvmInline value class RelationKnownMinimum internal constructor(val value: Int)

@ConsistentCopyVisibility data class RelationCompleteCoverage internal constructor(val exactCount: RelationExactCount)

enum class RelationIncompleteCoverageFailure {
    EMPTY_LIMITATIONS,
    CURSOR_REWIND,
    CURSOR_NOT_ADVANCED,
    PROVIDER_MISMATCH,
    PROVIDER_STATE_CURSOR_MISMATCH,
    PROVIDER_STATE_EXHAUSTED,
    PROVIDER_INVENTORY_MISMATCH,
    PROVIDER_STATE_NOT_ADVANCED,
    PROVIDER_STATE_AUTHORITY_MISMATCH,
}

/** Incomplete relation coverage, split by whether bounded provider work remains. */
sealed interface RelationIncompleteCoverage {
    val knownMinimum: RelationKnownMinimum
    val limitations: Set<RelationLimitation>

    data class Resumable
    internal constructor(
        override val knownMinimum: RelationKnownMinimum,
        override val limitations: Set<RelationLimitation>,
        val continuation: RelationContinuation,
    ) : RelationIncompleteCoverage

    data class TerminalIncomplete
    internal constructor(
        override val knownMinimum: RelationKnownMinimum,
        override val limitations: Set<RelationLimitation>,
    ) : RelationIncompleteCoverage

    companion object {
        /**
         * Proof transition: `(RelationBatch, Set<RelationLimitation>, RelationProviderCursor) ->
         * Refinement<RelationIncompleteCoverage, RelationIncompleteCoverageFailure>`.
         *
         * Establishes non-empty incomplete-coverage reasons, a known-minimum count, and a
         * selector/scope/meaning/authority-bound continuation that strictly advances enumeration.
         * [RelationIncompleteCoverageFailure] is the closed expected failure. Raw limitations and provider positions
         * may enter only from the bounded compiler collector.
         */
        fun resumable(
            batch: RelationBatch,
            limitations: Set<RelationLimitation>,
            nextProviderCursor: RelationProviderCursor,
            providerState: RelationProviderState,
        ): Refinement<RelationIncompleteCoverage, RelationIncompleteCoverageFailure> {
            if (limitations.isEmpty()) {
                return Refinement.Rejected(RelationIncompleteCoverageFailure.EMPTY_LIMITATIONS)
            }
            if (!batch.request.admitsProvider(nextProviderCursor.provider)) {
                return Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_MISMATCH)
            }
            if (nextProviderCursor.nextPosition.value < batch.request.providerCursor.nextPosition.value) {
                return Refinement.Rejected(RelationIncompleteCoverageFailure.CURSOR_REWIND)
            }
            if (nextProviderCursor.nextPosition.value == batch.request.providerCursor.nextPosition.value) {
                return Refinement.Rejected(RelationIncompleteCoverageFailure.CURSOR_NOT_ADVANCED)
            }
            if (providerState.providerCursor != nextProviderCursor) {
                return Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_CURSOR_MISMATCH)
            }
            val retained =
                when (val progress = admitProviderProgress(batch.request, providerState)) {
                    is Refinement.Rejected -> return progress
                    is Refinement.Refined -> progress.value
                }
            val orderedLimitations = limitations.toSortedSet(compareBy { it.ordinal }).toSet()
            return Refinement.Refined(
                Resumable(
                    knownMinimum = RelationKnownMinimum(batch.semanticResultCount),
                    limitations = orderedLimitations,
                    continuation =
                        RelationContinuation.issue(
                            batch.request,
                            nextProviderCursor,
                            orderedLimitations,
                            retained,
                        ),
                )
            )
        }

        fun terminal(
            batch: RelationBatch,
            limitations: Set<RelationLimitation>,
        ): Refinement<RelationIncompleteCoverage, RelationIncompleteCoverageFailure> =
            if (limitations.isEmpty()) {
                Refinement.Rejected(RelationIncompleteCoverageFailure.EMPTY_LIMITATIONS)
            } else {
                Refinement.Refined(
                    TerminalIncomplete(
                        knownMinimum = RelationKnownMinimum(batch.semanticResultCount),
                        limitations = limitations.toSortedSet(compareBy { it.ordinal }).toSet(),
                    )
                )
            }
    }
}

enum class RelationCompilerRejection {
    WORKSPACE_ROOT_MISMATCH,
    GENERATION_MOVED,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    COMPILER_IDENTITY_UNAVAILABLE,
    CONTINUATION_CURSOR_MOVED,
    COMPILER_CONTRACT_VIOLATION,
}

/** Closed output of the request-local compiler relation boundary. */
sealed interface RelationCompilation {
    @ConsistentCopyVisibility
    data class Complete
    internal constructor(
        val batch: RelationBatch,
        val coverage: RelationCompleteCoverage,
    ) : RelationCompilation

    @ConsistentCopyVisibility
    data class Qualified
    internal constructor(
        val batch: RelationBatch,
        val coverage: RelationIncompleteCoverage,
    ) : RelationCompilation

    data class Rejected(val reason: RelationCompilerRejection) : RelationCompilation

    companion object {
        /**
         * Proof transition: `RelationBatch + terminal compiler proof -> RelationCompilation.Complete`.
         *
         * Establishes exact count and permits empty evidence to mean absence. Only a limitation-free terminal compiler
         * collector may call this transition.
         */
        fun complete(batch: RelationBatch): Complete =
            Complete(
                batch,
                RelationCompleteCoverage(RelationExactCount(batch.semanticResultCount)),
            )

        /**
         * Proof transition: `(RelationBatch, Set<RelationLimitation>, RelationWorkOffset) ->
         * Refinement<RelationCompilation.Qualified, RelationIncompleteCoverageFailure>`.
         *
         * Establishes known-minimum evidence plus resumable incomplete coverage. Empty evidence remains qualified and
         * cannot represent absence. [RelationIncompleteCoverageFailure] is the closed expected failure. Raw provider
         * state may enter only from the bounded compiler collector.
         */
        fun qualifiedResumable(
            batch: RelationBatch,
            limitations: Set<RelationLimitation>,
            nextProviderCursor: RelationProviderCursor,
            providerState: RelationProviderState,
        ): Refinement<Qualified, RelationIncompleteCoverageFailure> =
            when (
                val coverage =
                    RelationIncompleteCoverage.resumable(
                        batch,
                        limitations,
                        nextProviderCursor,
                        providerState,
                    )
            ) {
                is Refinement.Refined -> Refinement.Refined(Qualified(batch, coverage.value))
                is Refinement.Rejected -> coverage
            }

        fun qualifiedTerminal(
            batch: RelationBatch,
            limitations: Set<RelationLimitation>,
        ): Refinement<Qualified, RelationIncompleteCoverageFailure> =
            when (val coverage = RelationIncompleteCoverage.terminal(batch, limitations)) {
                is Refinement.Refined -> Refinement.Refined(Qualified(batch, coverage.value))
                is Refinement.Rejected -> coverage
            }
    }
}

/** Internal semantic effect port; implementations must return detached compiler evidence only. */
fun interface RelationCompilerPort {
    /**
     * Proof transition: `RelationRequest -> RelationCompilation`.
     *
     * A non-rejected result establishes exact one-hop compiler evidence and either exact terminal or resumable
     * incomplete coverage. [RelationCompilerRejection] is the closed expected failure. Live compiler/platform values
     * remain inside the implementation call.
     */
    suspend fun read(request: RelationRequest): RelationCompilation
}

enum class RelationReadRejection {
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    COMPILER_IDENTITY_UNAVAILABLE,
    CONTINUATION_CURSOR_MOVED,
    COMPILER_CONTRACT_VIOLATION,
}

sealed interface RelationReadResult {
    data class Complete(
        val batch: RelationBatch,
        val coverage: RelationCompleteCoverage,
    ) : RelationReadResult

    data class Qualified(
        val batch: RelationBatch,
        val coverage: RelationIncompleteCoverage,
    ) : RelationReadResult

    data class Rejected(val reason: RelationReadRejection) : RelationReadResult
}

/** One-hop semantic engine composed by query and traversal. */
fun interface RelationOperations {
    /**
     * Proof transition: `RelationRequest -> RelationReadResult`.
     *
     * A complete or qualified result establishes current-authority, exact compiler-grounded one-hop evidence.
     * [RelationReadRejection] is the closed expected failure. Raw selector, endpoint, continuation, and budget inputs
     * may enter only before [RelationRequest] construction.
     */
    suspend fun read(request: RelationRequest): RelationReadResult
}

private fun admitProviderProgress(
    request: RelationRequest,
    providerState: RelationProviderState,
): Refinement<RelationProviderState, RelationIncompleteCoverageFailure> {
    val previous = (request.position as? RelationReadPosition.Resume)?.continuation?.providerState
    if (!request.admitsProvider(providerState.provider)) {
        return Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_MISMATCH)
    }
    if (providerState.confirmAuthority(request.subject.lease.identity) is Refinement.Rejected) {
        return Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_AUTHORITY_MISMATCH)
    }
    val progress = if (previous == null) providerState.confirmUnfinishedWork() else providerState.advanceFrom(previous)
    if (progress is Refinement.Rejected) {
        return Refinement.Rejected(
            when (progress.failure) {
                RelationProviderProgressFailure.EXHAUSTED -> RelationIncompleteCoverageFailure.PROVIDER_STATE_EXHAUSTED
                RelationProviderProgressFailure.INVENTORY_MISMATCH ->
                    RelationIncompleteCoverageFailure.PROVIDER_INVENTORY_MISMATCH
                RelationProviderProgressFailure.ORDINAL_NOT_ADVANCED ->
                    RelationIncompleteCoverageFailure.PROVIDER_STATE_NOT_ADVANCED
                RelationProviderProgressFailure.AUTHORITY_MISMATCH ->
                    RelationIncompleteCoverageFailure.PROVIDER_STATE_AUTHORITY_MISMATCH
            }
        )
    }
    return Refinement.Refined(providerState)
}
