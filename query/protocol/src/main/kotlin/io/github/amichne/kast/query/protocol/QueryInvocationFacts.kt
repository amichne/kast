package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Ordered facts and permanent qualifications from admitted pages, with conservative aggregate storage charging. */
internal class QueryInvocationFacts(
    private val lease: SemanticReadAuthority,
    private val policy: QueryInvocationPolicy,
) {
    private val pages = mutableListOf<SymbolInvocationPage>()
    private var observedEmptyRows: QueryRows? = null
    val hasObservedRowShape: Boolean
        get() = observedEmptyRows != null

    private val checkpoints = mutableSetOf<QueryCheckpoint>()
    private val limitations = linkedSetOf<QueryLimitation>()
    var retainedBytes = 0L
        private set

    var progress: QueryContinuationState = QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)

    fun append(page: SymbolInvocationPage, capacity: Long): Refinement<Unit, QueryInvocationTransition.Stopped> {
        val previous = pages.firstOrNull()?.result?.rows
        val current = page.result.rows
        if (previous != null && !compatibleRows(previous, current)) return Refinement.Rejected(invalidInvocation())
        if (observedEmptyRows == null) observedEmptyRows = emptyRows(current)
        val next =
            (page.execution as? QueryExecutionResult.Qualified)?.continuation as? QueryContinuationState.Resumable
        if (next != null && next.checkpoint in checkpoints)
            return Refinement.Rejected(QueryInvocationTransition.Stopped(QueryInvocationStop.NON_ADVANCING))
        val snapshot =
            when (val captured = QueryRetainedResult.capture(lease, page.execution)) {
                is Refinement.Refined -> captured.value
                is Refinement.Rejected -> return Refinement.Rejected(invalidInvocation())
            }
        val charge =
            snapshot.retainedBytes
                .saturatedAdd(policy.previewBytes(page.items))
                .saturatedAdd(page.items.size.toLong() * QUERY_ROW_REFERENCE_CHARGE_BYTES)
        if (charge > capacity - retainedBytes)
            return Refinement.Rejected(QueryInvocationTransition.Stopped(QueryInvocationStop.RETAINED_BYTES_LIMIT))
        retainedBytes += charge
        pages += page
        if (next != null) checkpoints += next.checkpoint
        if (page is SymbolInvocationPage.Qualified)
            limitations += page.execution.coverage.limitations.filter { it !in PAGE_LIMITS }
        return Refinement.Refined(Unit)
    }

    fun terminal(page: SymbolInvocationPage.Qualified, reason: QueryTerminalReason) {
        progress = QueryContinuationState.Terminal(reason)
        limitations += page.execution.coverage.limitations
    }

    fun finish(
        stopped: QueryInvocationTransition.Stopped,
        validProgress: QueryContinuationState,
    ): AccumulatedSymbolQuery {
        val facts = pages.map { it.result }
        val items = pages.flatMap { it.items }
        val combined =
            QueryResult(
                rows = combinedRows(facts.map { it.rows }),
                failures = facts.flatMap { it.failures },
                omissions = facts.flatMap { it.omissions },
                walkObservations = facts.flatMap { it.walkObservations },
                referenceObservations = facts.flatMap { it.referenceObservations },
                discoveryObservations = facts.flatMap { it.discoveryObservations },
                relationObservations = facts.flatMap { it.relationObservations },
            )
        val count = QueryCount.parse(items.size).required()
        val aggregate =
            if (stopped.reason == QueryInvocationStop.COMPLETED && limitations.isEmpty())
                QueryExecutionResult.Complete.create(combined, QueryCoverage.Complete(count))
            else {
                qualifyStop(stopped.reason)
                QueryExecutionResult.Qualified(
                    combined,
                    QueryCoverage.Qualified.create(count, limitations).required(),
                    validProgress,
                )
            }
        return AccumulatedSymbolQuery(aggregate, items, stopped.reason, stopped.failure)
    }

    private fun compatibleRows(left: QueryRows, right: QueryRows): Boolean =
        when (left) {
            is QueryRows.Symbols -> right is QueryRows.Symbols
            is QueryRows.Occurrences -> right is QueryRows.Occurrences
            is QueryRows.Bindings -> right is QueryRows.Bindings && left.mode == right.mode
            is QueryRows.ValuePaths,
            is QueryRows.ImpactWitness -> false
        }

    private fun emptyRows(rows: QueryRows): QueryRows =
        when (rows) {
            is QueryRows.Symbols -> QueryRows.Symbols.of(emptyList())
            is QueryRows.Occurrences -> QueryRows.Occurrences.of(emptyList())
            is QueryRows.Bindings -> QueryRows.Bindings.of(emptyList(), rows.mode)
            is QueryRows.ValuePaths,
            is QueryRows.ImpactWitness -> error("Invocation admitted an investigation ledger")
        }

    private fun combinedRows(rows: List<QueryRows>): QueryRows =
        when (val first = rows.firstOrNull()) {
            null -> observedEmptyRows ?: QueryRows.Symbols.of(emptyList())
            is QueryRows.Symbols -> QueryRows.Symbols.of(rows.flatMap { (it as QueryRows.Symbols).values })
            is QueryRows.Occurrences -> QueryRows.Occurrences.of(rows.flatMap { (it as QueryRows.Occurrences).values })
            is QueryRows.Bindings ->
                QueryRows.Bindings.of(rows.flatMap { (it as QueryRows.Bindings).values }, first.mode)
            is QueryRows.ValuePaths,
            is QueryRows.ImpactWitness -> error("Invocation admitted an investigation ledger")
        }

    private fun qualifyStop(stop: QueryInvocationStop) {
        when (stop) {
            QueryInvocationStop.TIME_LIMIT -> limitations += QueryLimitation.TIME_LIMIT_REACHED
            QueryInvocationStop.WORK_LIMIT -> limitations += QueryLimitation.WORK_LIMIT_REACHED
            QueryInvocationStop.RETAINED_BYTES_LIMIT,
            QueryInvocationStop.RETENTION_FAILED -> limitations += QueryLimitation.BYTE_LIMIT_REACHED
            QueryInvocationStop.COMPLETED,
            QueryInvocationStop.TERMINAL_INCOMPLETE -> Unit
            QueryInvocationStop.BUDGET_INCREASE_REQUIRED,
            QueryInvocationStop.INVALID_STATE,
            QueryInvocationStop.NON_ADVANCING,
            QueryInvocationStop.CANCELLED -> limitations += QueryLimitation.EXECUTION_INCOMPLETE
        }
    }

    companion object {
        private val PAGE_LIMITS =
            setOf(
                QueryLimitation.RESULT_LIMIT_REACHED,
                QueryLimitation.BYTE_LIMIT_REACHED,
                QueryLimitation.WORK_LIMIT_REACHED,
                QueryLimitation.TIME_LIMIT_REACHED,
            )
    }
}
