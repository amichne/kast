package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease

enum class QueryByteLimitFailure {
    NOT_POSITIVE,
}

@JvmInline
value class QueryByteLimit private constructor(
    val value: Long,
) {
    companion object {
        fun parse(raw: Long): Refinement<QueryByteLimit, QueryByteLimitFailure> =
            if (raw > 0L) {
                Refinement.Refined(QueryByteLimit(raw))
            } else {
                Refinement.Rejected(QueryByteLimitFailure.NOT_POSITIVE)
            }
    }
}

/** One plan-wide budget. Child discovery and relation calls receive only remaining authority. */
data class QueryBudget(
    val resources: ResourceBudget,
    val returnedBytes: QueryByteLimit,
)

enum class QueryExecutionRequestFailure {
    REFERENCE_LEASE_MISMATCH,
}

class QueryExecutionRequest private constructor(
    val plan: AdmittedQueryPlan,
    val lease: SemanticReadLease,
    val budget: QueryBudget,
) {
    companion object {
        fun create(
            plan: AdmittedQueryPlan,
            lease: SemanticReadLease,
            budget: QueryBudget,
        ): Refinement<QueryExecutionRequest, QueryExecutionRequestFailure> {
            val referenceLeases = when (plan) {
                is AdmittedQueryPlan.CandidateReferences -> plan.source.values.map { it.lease }
                is AdmittedQueryPlan.ExactReferences -> plan.source.values.map { it.lease }
                is AdmittedQueryPlan.Candidates,
                is AdmittedQueryPlan.Symbols,
                    -> emptyList()
            }
            return if (referenceLeases.any { it != lease }) {
                Refinement.Rejected(QueryExecutionRequestFailure.REFERENCE_LEASE_MISMATCH)
            } else {
                Refinement.Refined(QueryExecutionRequest(plan, lease, budget))
            }
        }
    }
}

data class QueryCandidate(
    val selection: SymbolDiscoverySelection,
)

/** Exact symbol plus every retained relation edge that established its presence. */
data class QuerySymbol(
    val description: SymbolDescription,
    val connections: List<RelationFact>,
) {
    val selector: SymbolSelector
        get() = description.selector
}

sealed interface QueryResultSet {
    data class Candidates(val values: List<QueryCandidate>) : QueryResultSet
    data class Symbols(val values: List<QuerySymbol>) : QueryResultSet
}

sealed interface QueryItemFailure {
    data class Refinement(
        val candidate: SymbolDiscoverySelection,
        val reason: SymbolExactRejection,
    ) : QueryItemFailure

    data class Visibility(
        val selector: SymbolSelector,
        val reason: SourceReadRejection,
    ) : QueryItemFailure

    data class ExactReference(
        val selector: SymbolSelector,
        val reason: SymbolExactRejection,
    ) : QueryItemFailure

    data class PredicateUnproven(
        val selector: SymbolSelector,
    ) : QueryItemFailure

    data class Relation(
        val selector: SymbolSelector,
        val meaning: RelationMeaning,
        val reason: RelationReadRejection,
    ) : QueryItemFailure
}

data class QueryResult(
    val items: QueryResultSet,
    val failures: List<QueryItemFailure>,
)

enum class QueryCountFailure {
    NEGATIVE,
}

@JvmInline
value class QueryCount private constructor(
    val value: Int,
) {
    companion object {
        fun parse(raw: Int): Refinement<QueryCount, QueryCountFailure> =
            if (raw < 0) {
                Refinement.Rejected(QueryCountFailure.NEGATIVE)
            } else {
                Refinement.Refined(QueryCount(raw))
            }
    }
}

enum class QueryLimitation {
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DISCOVERY_INCOMPLETE,
    REFINEMENT_INCOMPLETE,
    VISIBILITY_INCOMPLETE,
    RELATION_INCOMPLETE,
}

sealed interface QueryCoverage {
    data class Complete(val resultCount: QueryCount) : QueryCoverage

    class Qualified private constructor(
        val knownMinimum: QueryCount,
        val limitations: List<QueryLimitation>,
    ) : QueryCoverage {
        companion object {
            fun create(
                knownMinimum: QueryCount,
                limitations: Set<QueryLimitation>,
            ): Refinement<Qualified, QueryCollectionFailure> =
                if (limitations.isEmpty()) {
                    Refinement.Rejected(QueryCollectionFailure.EMPTY)
                } else {
                    Refinement.Refined(
                        Qualified(knownMinimum, limitations.sortedBy { it.ordinal }),
                    )
                }
        }

        override fun equals(other: Any?): Boolean =
            other is Qualified &&
                knownMinimum == other.knownMinimum &&
                limitations == other.limitations

        override fun hashCode(): Int = 31 * knownMinimum.hashCode() + limitations.hashCode()
    }
}

enum class QueryExecutionRejection {
    DISCOVERY_REJECTED,
    REFERENCE_STALE,
    BUDGET_REJECTED,
    INTERNAL_CONTRACT_VIOLATION,
}

sealed interface QueryExecutionResult {
    data class Complete(
        val result: QueryResult,
        val coverage: QueryCoverage.Complete,
    ) : QueryExecutionResult

    data class Qualified(
        val result: QueryResult,
        val coverage: QueryCoverage.Qualified,
    ) : QueryExecutionResult

    data class Rejected(
        val reason: QueryExecutionRejection,
        val discoveryReason: SymbolDiscoveryRejection? = null,
    ) : QueryExecutionResult
}
