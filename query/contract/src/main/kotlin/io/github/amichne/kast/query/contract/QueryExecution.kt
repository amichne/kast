package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.SourceTextWithheldReason
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolExactRejection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

enum class QueryByteLimitFailure {
    NOT_POSITIVE
}

@JvmInline
value class QueryByteLimit private constructor(val value: Long) {
    companion object {
        val DefaultCheckpoint =
            QueryByteLimit(
                io.github.amichne.kast.kernel.ReadLimitParameter.QUERY_CHECKPOINT_BYTES.defaultValue.toLong()
            )

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
    val checkpointBytes: QueryByteLimit = QueryByteLimit.DefaultCheckpoint,
)

enum class QueryExecutionRequestFailure {
    REFERENCE_LEASE_MISMATCH,
    CHECKPOINT_MISMATCH,
}

class QueryExecutionRequest
private constructor(
    val plan: AdmittedQueryPlan,
    val lease: SemanticReadAuthority,
    val budget: QueryBudget,
    val checkpoint: QueryCheckpoint?,
) {
    companion object {
        fun create(
            plan: AdmittedQueryPlan,
            lease: SemanticReadAuthority,
            budget: QueryBudget,
            checkpoint: QueryCheckpoint? = null,
        ): Refinement<QueryExecutionRequest, QueryExecutionRequestFailure> {
            val referenceLeases =
                when (plan) {
                    is AdmittedQueryPlan.ExactReferences -> plan.source.values.map { it.lease }
                    is AdmittedQueryPlan.Retained -> listOf(plan.source.lease)
                    is AdmittedQueryPlan.Symbols -> emptyList()
                }
            if (checkpoint != null && (checkpoint.plan != plan || checkpoint.lease != lease)) {
                return Refinement.Rejected(QueryExecutionRequestFailure.CHECKPOINT_MISMATCH)
            }
            return if ((referenceLeases + plan.composedInputLeases()).any { it != lease }) {
                Refinement.Rejected(QueryExecutionRequestFailure.REFERENCE_LEASE_MISMATCH)
            } else {
                Refinement.Refined(QueryExecutionRequest(plan, lease, budget, checkpoint))
            }
        }
    }
}

/** Exact symbol plus every retained relation edge that established its presence. */
data class QuerySymbol(
    val description: SymbolDescription,
    val connections: List<RelationFact>,
    val source: QuerySymbolSource = QuerySymbolSource.Pending,
    val arrival: QueryArrivalEvidence = QueryArrivalEvidence.None,
    val walkArrival: QueryWalkArrival = QueryWalkArrival.None,
) {
    init {
        require(arrival !is QueryArrivalEvidence.Proven || arrival.facts.all(connections::contains))
        require(walkArrival !is QueryWalkArrival.Proven || walkArrival.records.all { it.fact in connections })
    }

    val selector: SymbolSelector
        get() = description.selector
}

/** Source is obtained only after exact-symbol admission, within the same read authority. */
sealed interface QuerySymbolSource {
    data object Pending : QuerySymbolSource

    data class Returned(val value: SourceTextProjection.Returned) : QuerySymbolSource

    data class Rejected(val reason: SourceReadRejection) : QuerySymbolSource

    data class Withheld(val reason: SourceTextWithheldReason) : QuerySymbolSource
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

    data class PredicateUnproven(val selector: SymbolSelector) : QueryItemFailure

    data class Source(val selector: SymbolSelector, val reason: QuerySourceFailure) : QueryItemFailure

    data class Relation(
        val selector: SymbolSelector,
        val meaning: RelationMeaning,
        val reason: RelationReadRejection,
    ) : QueryItemFailure

    data class Walk(
        val selector: SymbolSelector,
        val meaning: RelationMeaning,
        val reason: TraversalRejection,
    ) : QueryItemFailure
}

sealed interface QuerySourceFailure {
    data class Rejected(val reason: SourceReadRejection) : QuerySourceFailure

    data class Withheld(val reason: SourceTextWithheldReason) : QuerySourceFailure
}

data class QueryResult(
    val items: List<QuerySymbol>,
    val failures: List<QueryItemFailure>,
    val omissions: List<QueryRelationOmission> = emptyList(),
    val walkObservations: List<QueryWalkObservation> = emptyList(),
)

enum class QueryCountFailure {
    NEGATIVE
}

@JvmInline
value class QueryCount private constructor(val value: Int) {
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
    SOURCE_INCOMPLETE,
    RELATION_INCOMPLETE,
    TRAVERSAL_INCOMPLETE,
    ROW_SELECTION_INCOMPLETE,
}

sealed interface QueryCoverage {
    data class Complete(val resultCount: QueryCount) : QueryCoverage

    class Qualified
    private constructor(
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
                    Refinement.Refined(Qualified(knownMinimum, limitations.sortedBy { it.ordinal }))
                }
        }

        override fun equals(other: Any?): Boolean =
            other is Qualified && knownMinimum == other.knownMinimum && limitations == other.limitations

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
        val continuation: QueryContinuationState =
            QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
    ) : QueryExecutionResult

    data class Rejected(
        val reason: QueryExecutionRejection,
        val discoveryReason: SymbolDiscoveryRejection? = null,
    ) : QueryExecutionResult
}

/** Detached execution proof. Implementations must retain no live compiler/PSI objects. */
interface QueryCheckpoint {
    val plan: AdmittedQueryPlan
    val lease: SemanticReadAuthority
    /** Conservative retained-state accounting used by bounded host stores. */
    val retainedBytes: Long
}

enum class QueryTerminalReason {
    UPSTREAM_INCOMPLETE,
    OUTPUT_ITEM_TOO_LARGE,
    CHECKPOINT_CAPACITY_EXCEEDED,
    NO_PROGRESS,
}

sealed interface QueryContinuationState {
    data class Resumable(val checkpoint: QueryCheckpoint) : QueryContinuationState

    data class Terminal(val reason: QueryTerminalReason) : QueryContinuationState
}
