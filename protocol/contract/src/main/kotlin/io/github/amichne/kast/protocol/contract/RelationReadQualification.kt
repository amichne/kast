package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

enum class RelationReadQualificationFailure {
    EMPTY_LIMITATIONS,
    NON_CANONICAL_LIMITATIONS,
    CONTINUATION_KIND_MISMATCH,
    RETAINED_COVERAGE_CONFLICT,
    UNSUPPORTED_NEXT_ACTION,
}

/** Exact incomplete relation coverage, distinguishing upstream work from retained detached output. */
sealed interface RelationReadQualification : OperationQualification {
    val knownMinimum: RelationKnownMinimumDocument
    val limitations: List<RelationLimitationDocument>

    @ConsistentCopyVisibility
    data class Resumable
    internal constructor(
        override val knownMinimum: RelationKnownMinimumDocument,
        override val limitations: List<RelationLimitationDocument>,
        val checkpoint: RelationCheckpointDocument,
        val nextAction: ReadResumeActionDocument,
    ) : RelationReadQualification {
        val continuation: RelationContinuationDocument
            get() = checkpoint.token
    }

    @ConsistentCopyVisibility
    data class TerminalIncomplete
    internal constructor(
        override val knownMinimum: RelationKnownMinimumDocument,
        override val limitations: List<RelationLimitationDocument>,
    ) : RelationReadQualification

    companion object {
        fun resumable(
            knownMinimum: RelationKnownMinimumDocument,
            limitations: List<RelationLimitationDocument>,
            continuation: RelationContinuationDocument,
            nextAction: ReadResumeActionDocument = ReadResumeActionDocument.RESUME,
        ): Refinement<Resumable, RelationReadQualificationFailure> =
            admitResumable(knownMinimum, limitations, RelationCheckpointDocument.Upstream(continuation), nextAction)

        fun admitResumable(
            knownMinimum: RelationKnownMinimumDocument,
            limitations: List<RelationLimitationDocument>,
            checkpoint: RelationCheckpointDocument,
            nextAction: ReadResumeActionDocument,
        ): Refinement<Resumable, RelationReadQualificationFailure> =
            when (val admitted = admitRelationLimitations(limitations)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined ->
                    when (val progress = checkpoint.admitCoverage(admitted.value, nextAction)) {
                        is Refinement.Rejected -> progress
                        is Refinement.Refined ->
                            Refinement.Refined(Resumable(knownMinimum, admitted.value, progress.value, nextAction))
                    }
            }

        fun terminalIncomplete(
            knownMinimum: RelationKnownMinimumDocument,
            limitations: List<RelationLimitationDocument>,
        ): Refinement<TerminalIncomplete, RelationReadQualificationFailure> =
            when (val admitted = admitRelationLimitations(limitations)) {
                is Refinement.Refined -> Refinement.Refined(TerminalIncomplete(knownMinimum, admitted.value))
                is Refinement.Rejected -> admitted
            }
    }
}

private fun admitRelationLimitations(
    limitations: List<RelationLimitationDocument>
): Refinement<List<RelationLimitationDocument>, RelationReadQualificationFailure> =
    when {
        limitations.isEmpty() -> Refinement.Rejected(RelationReadQualificationFailure.EMPTY_LIMITATIONS)
        limitations != limitations.distinct().sortedBy { it.ordinal } ->
            Refinement.Rejected(RelationReadQualificationFailure.NON_CANONICAL_LIMITATIONS)
        else -> Refinement.Refined(java.util.List.copyOf(limitations))
    }
