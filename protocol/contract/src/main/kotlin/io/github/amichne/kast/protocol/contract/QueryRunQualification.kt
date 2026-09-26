package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

enum class QueryLimitationDocument {
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DISCOVERY_INCOMPLETE,
    REFINEMENT_INCOMPLETE,
    VISIBILITY_INCOMPLETE,
    SOURCE_INCOMPLETE,
    RELATION_INCOMPLETE,
    ROW_SELECTION_INCOMPLETE,
}

enum class QueryKnownMinimumFailure {
    NEGATIVE
}

@JvmInline
value class QueryKnownMinimum private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<QueryKnownMinimum, QueryKnownMinimumFailure> =
            if (raw < 0) Refinement.Rejected(QueryKnownMinimumFailure.NEGATIVE)
            else Refinement.Refined(QueryKnownMinimum(raw))
    }
}

enum class QueryRunQualificationFailure {
    EMPTY_LIMITATIONS,
    NON_CANONICAL_LIMITATIONS,
}

class QueryRunQualification
private constructor(
    val knownMinimum: QueryKnownMinimum,
    val limitations: List<QueryLimitationDocument>,
    val progress: QueryQualifiedProgressDocument,
) : OperationQualification {
    companion object {
        fun create(
            knownMinimum: QueryKnownMinimum,
            limitations: List<QueryLimitationDocument>,
            progress: QueryQualifiedProgressDocument,
        ): Refinement<QueryRunQualification, QueryRunQualificationFailure> =
            when {
                limitations.isEmpty() -> Refinement.Rejected(QueryRunQualificationFailure.EMPTY_LIMITATIONS)
                limitations != limitations.distinct().sortedBy { it.ordinal } ->
                    Refinement.Rejected(QueryRunQualificationFailure.NON_CANONICAL_LIMITATIONS)
                else -> Refinement.Refined(QueryRunQualification(knownMinimum, limitations.toList(), progress))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is QueryRunQualification &&
            knownMinimum == other.knownMinimum &&
            limitations == other.limitations &&
            progress == other.progress

    override fun hashCode(): Int = 31 * (31 * knownMinimum.hashCode() + limitations.hashCode()) + progress.hashCode()
}
