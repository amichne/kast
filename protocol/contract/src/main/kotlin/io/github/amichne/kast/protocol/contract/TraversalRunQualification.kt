package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

enum class TraversalLimitationDocument {
    RECORD_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DEPTH_LIMIT_REACHED,
    FRONTIER_LIMIT_REACHED,
    ONE_HOP_INCOMPLETE,
    NO_PROGRESS,
}

enum class TraversalRunQualificationFailure {
    EMPTY_LIMITATIONS,
    NON_CANONICAL_LIMITATIONS,
    NON_CANONICAL_RELATION_LIMITATIONS,
    MISSING_RELATION_LIMITATIONS,
    UNEXPECTED_RELATION_LIMITATIONS,
    TERMINAL_LIMITATION_RESUMABLE,
    TERMINAL_WITHOUT_TERMINAL_LIMITATION,
    CONTINUATION_KIND_MISMATCH,
    RETAINED_COVERAGE_CONFLICT,
}

/** Incomplete traversal coverage, split by whether additional deterministic work remains. */
sealed interface TraversalRunQualification : OperationQualification {
    val limitations: List<TraversalLimitationDocument>
    val relationLimitations: List<RelationLimitationDocument>

    @ConsistentCopyVisibility
    data class Resumable
    internal constructor(
        override val limitations: List<TraversalLimitationDocument>,
        override val relationLimitations: List<RelationLimitationDocument>,
        val checkpoint: TraversalCheckpointDocument,
        val nextAction: ReadResumeActionDocument,
    ) : TraversalRunQualification {
        val continuation: TraversalContinuationDocument
            get() = checkpoint.token
    }

    @ConsistentCopyVisibility
    data class TerminalIncomplete
    internal constructor(
        override val limitations: List<TraversalLimitationDocument>,
        override val relationLimitations: List<RelationLimitationDocument>,
    ) : TraversalRunQualification

    companion object {
        fun resumable(
            limitations: List<TraversalLimitationDocument>,
            relationLimitations: List<RelationLimitationDocument>,
            continuation: TraversalContinuationDocument,
            nextAction: ReadResumeActionDocument = ReadResumeActionDocument.RESUME,
        ): Refinement<Resumable, TraversalRunQualificationFailure> =
            admitResumable(
                limitations,
                relationLimitations,
                TraversalCheckpointDocument.Upstream(continuation),
                nextAction,
            )

        fun admitResumable(
            limitations: List<TraversalLimitationDocument>,
            relationLimitations: List<RelationLimitationDocument>,
            checkpoint: TraversalCheckpointDocument,
            nextAction: ReadResumeActionDocument,
        ): Refinement<Resumable, TraversalRunQualificationFailure> =
            when (val admitted = admitTraversalLimitations(limitations, relationLimitations)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined ->
                    when (val cursor = checkpoint.admitCoverage(admitted.value.first)) {
                        is Refinement.Rejected -> cursor
                        is Refinement.Refined ->
                            Refinement.Refined(
                                Resumable(admitted.value.first, admitted.value.second, cursor.value, nextAction)
                            )
                    }
            }

        fun terminalIncomplete(
            limitations: List<TraversalLimitationDocument>,
            relationLimitations: List<RelationLimitationDocument>,
        ): Refinement<TerminalIncomplete, TraversalRunQualificationFailure> =
            when (val admitted = admitTraversalLimitations(limitations, relationLimitations)) {
                is Refinement.Refined ->
                    if (!admitted.value.first.hasTerminalTraversalCause()) {
                        Refinement.Rejected(TraversalRunQualificationFailure.TERMINAL_WITHOUT_TERMINAL_LIMITATION)
                    } else {
                        Refinement.Refined(TerminalIncomplete(admitted.value.first, admitted.value.second))
                    }
                is Refinement.Rejected -> admitted
            }
    }
}

private fun admitTraversalLimitations(
    limitations: List<TraversalLimitationDocument>,
    relationLimitations: List<RelationLimitationDocument>,
): Refinement<
    Pair<List<TraversalLimitationDocument>, List<RelationLimitationDocument>>,
    TraversalRunQualificationFailure,
> {
    if (limitations.isEmpty()) {
        return Refinement.Rejected(TraversalRunQualificationFailure.EMPTY_LIMITATIONS)
    }
    if (limitations != limitations.distinct().sortedBy { it.ordinal }) {
        return Refinement.Rejected(TraversalRunQualificationFailure.NON_CANONICAL_LIMITATIONS)
    }
    if (relationLimitations != relationLimitations.distinct().sortedBy { it.ordinal }) {
        return Refinement.Rejected(TraversalRunQualificationFailure.NON_CANONICAL_RELATION_LIMITATIONS)
    }
    val oneHopIncomplete = TraversalLimitationDocument.ONE_HOP_INCOMPLETE in limitations
    if (oneHopIncomplete && relationLimitations.isEmpty()) {
        return Refinement.Rejected(TraversalRunQualificationFailure.MISSING_RELATION_LIMITATIONS)
    }
    if (!oneHopIncomplete && relationLimitations.isNotEmpty()) {
        return Refinement.Rejected(TraversalRunQualificationFailure.UNEXPECTED_RELATION_LIMITATIONS)
    }
    return Refinement.Refined(java.util.List.copyOf(limitations) to java.util.List.copyOf(relationLimitations))
}
