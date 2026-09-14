package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation

enum class TraversalLimitation {
    RECORD_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DEPTH_LIMIT_REACHED,
    FRONTIER_LIMIT_REACHED,
    ONE_HOP_INCOMPLETE,
    NO_PROGRESS,
}

enum class TraversalQualificationFailure {
    EMPTY_LIMITATIONS,
    MISSING_RELATION_LIMITATION,
    UNEXPECTED_RELATION_LIMITATION,
    CONTINUATION_MISMATCH,
    TERMINAL_LIMITATION_RESUMABLE,
    TERMINAL_WITHOUT_TERMINAL_LIMITATION,
    CHECKPOINT_NOT_ADVANCED,
    ELAPSED_OVERRUN_UNQUALIFIED,
}

sealed interface TraversalQualification {
    val limitations: Set<TraversalLimitation>
    val relationLimitations: Set<RelationLimitation>

    class Resumable
    internal constructor(
        override val limitations: Set<TraversalLimitation>,
        override val relationLimitations: Set<RelationLimitation>,
        val continuation: TraversalContinuation,
    ) : TraversalQualification

    class TerminalIncomplete
    internal constructor(
        override val limitations: Set<TraversalLimitation>,
        override val relationLimitations: Set<RelationLimitation>,
    ) : TraversalQualification

    companion object {
        /**
         * Proof transition: `(TraversalPage, limitations, relation limitations, TraversalContinuation) ->
         * Refinement<TraversalQualification, TraversalQualificationFailure>`.
         *
         * Establishes non-empty ordered qualification reasons, exact one-hop limitations when applicable, and
         * continuation identity equal to the page plan. [TraversalQualificationFailure] is the closed expected failure.
         * Raw limitation collections may enter only from the pure traversal engine or transport.
         */
        fun resumable(
            page: TraversalPage,
            limitations: Set<TraversalLimitation>,
            relationLimitations: Set<RelationLimitation>,
            continuation: TraversalContinuation,
        ): Refinement<Resumable, TraversalQualificationFailure> =
            when (val admitted = admitTraversalLimitations(limitations, relationLimitations)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined ->
                    if (
                        TraversalLimitation.DEPTH_LIMIT_REACHED in admitted.value.first ||
                            TraversalLimitation.NO_PROGRESS in admitted.value.first
                    ) {
                        Refinement.Rejected(TraversalQualificationFailure.TERMINAL_LIMITATION_RESUMABLE)
                    } else if (
                        continuation.checkpoint.progress.checkpointSequence <=
                            when (val position = page.plan.position) {
                                TraversalPosition.Start -> 0L
                                is TraversalPosition.Resume ->
                                    position.continuation.checkpoint.progress.checkpointSequence
                            }
                    ) {
                        Refinement.Rejected(TraversalQualificationFailure.CHECKPOINT_NOT_ADVANCED)
                    } else if (continuation.identity != page.plan.identity) {
                        Refinement.Rejected(TraversalQualificationFailure.CONTINUATION_MISMATCH)
                    } else {
                        Refinement.Refined(
                            Resumable(
                                admitted.value.first,
                                admitted.value.second,
                                continuation,
                            )
                        )
                    }
            }

        fun terminalIncomplete(
            limitations: Set<TraversalLimitation>,
            relationLimitations: Set<RelationLimitation>,
        ): Refinement<TerminalIncomplete, TraversalQualificationFailure> =
            when (val admitted = admitTraversalLimitations(limitations, relationLimitations)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined ->
                    if (
                        setOf(
                                TraversalLimitation.ONE_HOP_INCOMPLETE,
                                TraversalLimitation.DEPTH_LIMIT_REACHED,
                                TraversalLimitation.NO_PROGRESS,
                                TraversalLimitation.TIME_LIMIT_REACHED,
                            )
                            .none { it in admitted.value.first }
                    ) {
                        Refinement.Rejected(TraversalQualificationFailure.TERMINAL_WITHOUT_TERMINAL_LIMITATION)
                    } else {
                        Refinement.Refined(
                            TerminalIncomplete(
                                admitted.value.first,
                                admitted.value.second,
                            )
                        )
                    }
            }
    }
}

private fun admitTraversalLimitations(
    limitations: Set<TraversalLimitation>,
    relationLimitations: Set<RelationLimitation>,
): Refinement<
    Pair<Set<TraversalLimitation>, Set<RelationLimitation>>,
    TraversalQualificationFailure,
> =
    when {
        limitations.isEmpty() -> Refinement.Rejected(TraversalQualificationFailure.EMPTY_LIMITATIONS)
        TraversalLimitation.ONE_HOP_INCOMPLETE in limitations && relationLimitations.isEmpty() ->
            Refinement.Rejected(TraversalQualificationFailure.MISSING_RELATION_LIMITATION)
        TraversalLimitation.ONE_HOP_INCOMPLETE !in limitations && relationLimitations.isNotEmpty() ->
            Refinement.Rejected(TraversalQualificationFailure.UNEXPECTED_RELATION_LIMITATION)
        else ->
            Refinement.Refined(
                limitations.toSortedSet(compareBy { it.ordinal }).toSet() to
                    relationLimitations.toSortedSet(compareBy { it.ordinal }).toSet()
            )
    }
