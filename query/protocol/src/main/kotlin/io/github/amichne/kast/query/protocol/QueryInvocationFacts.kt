package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.Collections
import java.util.IdentityHashMap

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
    private var impactGraph = QueryImpactRetainedGraph()
    private var investigation: QueryImpactLedger? = null
    private var originalOrdinals: Map<QueryImpactPath, Int> = emptyMap()
    private val selectedPaths = Collections.newSetFromMap(IdentityHashMap<QueryImpactPath, Boolean>())
    var retentionEstimate = QueryInvocationFactsRetentionEstimate.Empty
        private set

    val retainedBytes: Long
        get() = retentionEstimate.total.value

    fun providerRetentionLedger() = impactGraph.providerRetentionLedger()

    var progress: QueryContinuationState = QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)

    fun append(page: SymbolInvocationPage, capacity: Long): Refinement<Unit, QueryInvocationTransition.Stopped> {
        val previous = pages.firstOrNull()?.result?.rows
        val current = page.result.rows
        if (previous != null && !compatibleRows(previous, current)) return Refinement.Rejected(invalidInvocation())
        val next =
            (page.execution as? QueryExecutionResult.Qualified)?.continuation as? QueryContinuationState.Resumable
        if (next != null && next.checkpoint in checkpoints)
            return Refinement.Rejected(QueryInvocationTransition.Stopped(QueryInvocationStop.NON_ADVANCING))
        if (observedEmptyRows == null && current !is QueryRows.ValuePaths) observedEmptyRows = emptyRows(current)
        val selection =
            when (val admitted = admitSelection(page.result.rows)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val graphTransaction = impactGraph.transaction()
        val charge =
            when (val captured = storageCharge(page, selection, graphTransaction.graph)) {
                is Refinement.Refined -> captured.value
                is Refinement.Rejected -> return captured
            }
        if (charge.total.value > capacity - retainedBytes)
            return Refinement.Rejected(QueryInvocationTransition.Stopped(QueryInvocationStop.RETAINED_BYTES_LIMIT))
        retentionEstimate += charge
        if (observedEmptyRows == null) observedEmptyRows = emptyRows(current)
        if (current is QueryRows.ValuePaths) {
            investigation = selection.ledger
            originalOrdinals = selection.ordinals
            selectedPaths += current.values
        }
        graphTransaction.commit()
        recordPage(page, current, next)
        return Refinement.Refined(Unit)
    }

    private fun recordPage(page: SymbolInvocationPage, current: QueryRows, next: QueryContinuationState.Resumable?) {
        pages += page
        if (next != null) checkpoints += next.checkpoint
        if (page is SymbolInvocationPage.Qualified)
            limitations +=
                page.execution.coverage.limitations.filter {
                    it !in PAGE_LIMITS &&
                        !(current is QueryRows.ValuePaths &&
                            (it == QueryLimitation.ROW_SELECTION_INCOMPLETE ||
                                (it == QueryLimitation.IMPACT_COVERAGE_UNPROVEN && investigation == null)))
                }
    }

    private data class ImpactSelection(
        val ledger: QueryImpactLedger?,
        val ordinals: Map<QueryImpactPath, Int>,
        val newLedger: Boolean,
    )

    private fun admitSelection(current: QueryRows): Refinement<ImpactSelection, QueryInvocationTransition.Stopped> {
        if (current !is QueryRows.ValuePaths) return Refinement.Refined(ImpactSelection(null, emptyMap(), false))
        val ledger = (current.accounting as? QueryValuePathAccounting.Investigated)?.ledger
        if (ledger == null) {
            return if (investigation != null || current.values.isNotEmpty()) Refinement.Rejected(invalidInvocation())
            else Refinement.Refined(ImpactSelection(null, emptyMap(), false))
        }
        if (investigation != null && investigation !== ledger) return Refinement.Rejected(invalidInvocation())
        val newLedger = investigation == null
        val candidateOrdinals =
            if (newLedger)
                IdentityHashMap<QueryImpactPath, Int>().apply {
                    ledger.paths.forEachIndexed { index, path -> put(path, index) }
                }
            else originalOrdinals
        if (current.values.any { it !in candidateOrdinals || it in selectedPaths })
            return Refinement.Rejected(invalidInvocation())
        return Refinement.Refined(ImpactSelection(ledger, candidateOrdinals, newLedger))
    }

    private fun storageCharge(
        page: SymbolInvocationPage,
        selection: ImpactSelection,
        graph: QueryImpactRetainedGraph,
    ): Refinement<QueryInvocationFactsRetentionEstimate, QueryInvocationTransition.Stopped> {
        val rows = page.result.rows
        val snapshot =
            when (val captured = QueryRetainedResult.capture(lease, page.execution)) {
                is Refinement.Refined -> captured.value
                is Refinement.Rejected -> return Refinement.Rejected(invalidInvocation())
            }
        val snapshotBytes =
            (if (rows is QueryRows.ValuePaths) {
                (snapshot as QueryRetainedResult.ValuePaths)
                    .retainedBytes(graph)
                    .saturatedAdd(rows.values.size.toLong() * QUERY_ROW_REFERENCE_CHARGE_BYTES * 2L)
                    .saturatedAdd(
                        if (selection.newLedger)
                            selection.ordinals.size.toLong() * QUERY_ROW_REFERENCE_CHARGE_BYTES * 2L
                        else 0L
                    )
            } else snapshot.retainedBytes)
        val checkpoint =
            ((page.execution as? QueryExecutionResult.Qualified)?.continuation as? QueryContinuationState.Resumable)
                ?.checkpoint
        val semanticBytes =
            if (checkpoint == null || snapshotBytes == Long.MAX_VALUE) snapshotBytes
            else {
                val standaloneCheckpoint = checkpoint.retainedBytes
                if (standaloneCheckpoint > snapshotBytes) return Refinement.Rejected(invalidInvocation())
                (snapshotBytes - standaloneCheckpoint).saturatedAdd(checkpoint.retainedBytes(graph))
            }
        return Refinement.Refined(
            QueryInvocationFactsRetentionEstimate(
                QueryRetentionByteCount.measured(semanticBytes),
                QueryRetentionByteCount.measured(policy.previewBytes(page.items)),
                QueryRetentionByteCount.measured(page.items.size.toLong() * QUERY_ROW_REFERENCE_CHARGE_BYTES),
            )
        )
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
            is QueryRows.ValuePaths -> right is QueryRows.ValuePaths
            is QueryRows.ImpactWitness -> false
        }

    private fun emptyRows(rows: QueryRows): QueryRows =
        when (rows) {
            is QueryRows.Symbols -> QueryRows.Symbols.of(emptyList())
            is QueryRows.Occurrences -> QueryRows.Occurrences.of(emptyList())
            is QueryRows.Bindings -> QueryRows.Bindings.of(emptyList(), rows.mode)
            is QueryRows.ValuePaths -> rows.selectRows(emptyList()).required()
            is QueryRows.ImpactWitness -> error("Invocation admitted presentation-only rows")
        }

    private fun combinedRows(rows: List<QueryRows>): QueryRows =
        when (val first = rows.firstOrNull()) {
            null -> observedEmptyRows ?: QueryRows.Symbols.of(emptyList())
            is QueryRows.Symbols -> QueryRows.Symbols.of(rows.flatMap { (it as QueryRows.Symbols).values })
            is QueryRows.Occurrences -> QueryRows.Occurrences.of(rows.flatMap { (it as QueryRows.Occurrences).values })
            is QueryRows.Bindings ->
                QueryRows.Bindings.of(rows.flatMap { (it as QueryRows.Bindings).values }, first.mode)
            is QueryRows.ValuePaths -> {
                val ledger = investigation
                if (ledger == null) QueryRows.ValuePaths.of(emptyList())
                else {
                    val original = QueryRows.ValuePaths.fromInvestigation(ledger).required()
                    val selected = rows.flatMap { (it as QueryRows.ValuePaths).values }
                    original.selectRows(selected.map { originalOrdinals.getValue(it) }).required()
                }
            }
            is QueryRows.ImpactWitness -> error("Invocation admitted presentation-only rows")
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
