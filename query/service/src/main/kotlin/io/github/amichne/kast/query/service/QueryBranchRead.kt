package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryBranch
import io.github.amichne.kast.query.contract.QueryBranchAccounting
import io.github.amichne.kast.query.contract.QueryBranchObservation
import io.github.amichne.kast.query.contract.QueryBranchOutcome
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.nanoseconds

/** Request-local measurement around one actual branch read, with no additional work charge. */
internal class QueryBranchRead {
    var outcome: QueryBranchOutcome = QueryBranchOutcome.FAILED
        private set

    var facts: Int = 0
        private set

    var limitations: Set<QueryLimitation> = emptySet()
        private set

    var examinedWorkUnits: QueryWorkUsage = QueryWorkUsage.Unobserved
        private set

    fun measured(work: Long) {
        examinedWorkUnits = QueryWorkUsage.Observed(QueryWorkCount.parse(work).refined())
    }

    fun returned(outcome: QueryBranchOutcome, facts: Int, limitations: Set<QueryLimitation> = emptySet()) {
        this.outcome = outcome
        this.facts = facts
        this.limitations = limitations.toSet()
    }
}

internal class QueryBranchObserver(
    private val state: QueryExecutionState,
    private val clock: QueryNanoClock,
    private val observation: QueryBranchObservation,
) {
    suspend fun <Value> read(
        branch: QueryBranch,
        source: RelationEndpoint,
        expansion: RelationSearchBoundary,
        workBefore: QueryWorkCount = state.consumedWork(),
        operation: suspend (QueryBranchRead) -> Value,
    ): Value {
        val started = clock.now()
        val read = QueryBranchRead()
        var outcome = QueryBranchOutcome.FAILED
        try {
            val result = operation(read)
            outcome = read.outcome
            return result
        } catch (cancelled: CancellationException) {
            outcome = QueryBranchOutcome.CANCELLED
            throw cancelled
        } finally {
            observation.observe(
                QueryBranchAccounting(
                    branch,
                    source,
                    expansion,
                    QueryWorkCount.parse(state.consumedWork().value - workBefore.value).refined(),
                    read.examinedWorkUnits,
                    (clock.now() - started).coerceAtLeast(0L).nanoseconds,
                    QueryCount.parse(read.facts).refined(),
                    outcome,
                    read.limitations,
                )
            )
        }
    }
}

internal fun RelationMeaning.queryBranch(): QueryBranch =
    when (this) {
        RelationMeaning.References -> QueryBranch.REFERENCES
        RelationMeaning.Callers -> QueryBranch.CALLERS
        RelationMeaning.Callees -> QueryBranch.CALLEES
        RelationMeaning.Implementations -> QueryBranch.IMPLEMENTATIONS
        RelationMeaning.Inheritors -> QueryBranch.INHERITORS
        RelationMeaning.Overrides -> QueryBranch.OVERRIDES
        RelationMeaning.TypeUses -> QueryBranch.TYPE_USES
    }

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Branch accounting lost its nonnegative measurement: $failure")
    }
