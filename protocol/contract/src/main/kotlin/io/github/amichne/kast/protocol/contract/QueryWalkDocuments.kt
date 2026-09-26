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

enum class QueryExpandedFrontierFailure {
    NEGATIVE
}

/** Nodes expanded on this traversal page, including expansions that emitted no edge. */
@JvmInline
value class QueryExpandedFrontierDocument private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<QueryExpandedFrontierDocument, QueryExpandedFrontierFailure> =
            if (raw >= 0) Refinement.Refined(QueryExpandedFrontierDocument(raw))
            else Refinement.Rejected(QueryExpandedFrontierFailure.NEGATIVE)
    }
}

enum class QueryWalkCoverageFailure {
    EMPTY_LIMITATIONS,
    NON_CANONICAL_LIMITATIONS,
    RELATION_LIMITATIONS_MISMATCH,
}

/** Page coverage without a second public continuation: query owns the resumable execution state. */
sealed interface QueryWalkCoverageDocument {
    data object Complete : QueryWalkCoverageDocument

    @ConsistentCopyVisibility
    data class Resumable
    internal constructor(
        val limitations: List<TraversalLimitationDocument>,
        val relationLimitations: List<RelationLimitationDocument>,
    ) : QueryWalkCoverageDocument

    @ConsistentCopyVisibility
    data class TerminalIncomplete
    internal constructor(
        val limitations: List<TraversalLimitationDocument>,
        val relationLimitations: List<RelationLimitationDocument>,
    ) : QueryWalkCoverageDocument

    companion object {
        fun resumable(
            limitations: List<TraversalLimitationDocument>,
            relationLimitations: List<RelationLimitationDocument>,
        ): Refinement<Resumable, QueryWalkCoverageFailure> =
            when (val admitted = admitLimitations(limitations, relationLimitations)) {
                is Refinement.Refined -> Refinement.Refined(Resumable(admitted.value.first, admitted.value.second))
                is Refinement.Rejected -> admitted
            }

        fun terminalIncomplete(
            limitations: List<TraversalLimitationDocument>,
            relationLimitations: List<RelationLimitationDocument>,
        ): Refinement<TerminalIncomplete, QueryWalkCoverageFailure> =
            when (val admitted = admitLimitations(limitations, relationLimitations)) {
                is Refinement.Refined ->
                    Refinement.Refined(TerminalIncomplete(admitted.value.first, admitted.value.second))
                is Refinement.Rejected -> admitted
            }

        private fun admitLimitations(
            limitations: List<TraversalLimitationDocument>,
            relationLimitations: List<RelationLimitationDocument>,
        ): Refinement<
            Pair<List<TraversalLimitationDocument>, List<RelationLimitationDocument>>,
            QueryWalkCoverageFailure,
        > =
            when {
                limitations.isEmpty() -> Refinement.Rejected(QueryWalkCoverageFailure.EMPTY_LIMITATIONS)
                limitations != limitations.distinct().sortedBy { it.ordinal } ||
                    relationLimitations != relationLimitations.distinct().sortedBy { it.ordinal } ->
                    Refinement.Rejected(QueryWalkCoverageFailure.NON_CANONICAL_LIMITATIONS)
                (TraversalLimitationDocument.ONE_HOP_INCOMPLETE in limitations) != relationLimitations.isNotEmpty() ->
                    Refinement.Rejected(QueryWalkCoverageFailure.RELATION_LIMITATIONS_MISMATCH)
                else ->
                    Refinement.Refined(java.util.List.copyOf(limitations) to java.util.List.copyOf(relationLimitations))
            }
    }
}

/** One committed traversal page, including empty leaves and qualified partial expansions. */
data class QueryWalkObservationDocument(
    val subject: QueryReferenceDocument.ExactSymbol,
    val relation: RelationKindDocument,
    val maximumDepth: ProtocolCount,
    val expandedFrontier: QueryExpandedFrontierDocument,
    val progress: TraversalProgressDocument,
    val strategy: TraversalStrategyDocument,
    val partialExpansions: BoundedProtocolList<TraversalPartialExpansionDocument>,
    val coverage: QueryWalkCoverageDocument,
) {
    companion object {
        val Empty: BoundedProtocolList<QueryWalkObservationDocument> =
            (BoundedProtocolList.create(emptyList<QueryWalkObservationDocument>()) as Refinement.Refined).value
    }
}
