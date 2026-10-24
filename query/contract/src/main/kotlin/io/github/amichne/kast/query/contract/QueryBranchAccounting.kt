package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import kotlin.time.Duration

/** Finite read roles at the shared query branch owner, not native implementation names. */
enum class QueryBranch {
    REFERENCES,
    CALLERS,
    CALLEES,
    IMPLEMENTATIONS,
    INHERITORS,
    OVERRIDES,
    TYPE_USES,
    TRACE_MEMBERS,
    TRACE_MEMBER_REFINEMENT,
}

enum class QueryBranchOutcome {
    COMPLETE,
    QUALIFIED,
    REJECTED,
    CANCELLED,
    FAILED,
}

/** One read receipt. Observing it grants no authority and does not retain compiler session objects. */
data class QueryBranchAccounting(
    val branch: QueryBranch,
    val source: RelationEndpoint,
    val expansion: RelationSearchBoundary,
    val chargedWorkUnits: QueryWorkCount,
    val examinedWorkUnits: QueryWorkUsage,
    val elapsed: Duration,
    val emittedFacts: QueryCount,
    val outcome: QueryBranchOutcome,
    val limitations: Set<QueryLimitation>,
) {
    init {
        require(!elapsed.isNegative())
    }

    val reads: Int = 1
    val effectiveScope = expansion.effectiveScope(source)
    val effectiveConstraints = expansion.effectiveConstraints(source)
    val domainFingerprint = RelationScopeFingerprint.from(source, expansion)
}

/** Streaming receipts bound retained diagnostic memory independently of the number of semantic reads. */
fun interface QueryBranchObservation {
    fun observe(accounting: QueryBranchAccounting)

    companion object {
        val None = QueryBranchObservation {}
    }
}
