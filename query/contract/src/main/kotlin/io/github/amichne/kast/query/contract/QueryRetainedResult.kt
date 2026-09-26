package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.Collections

private const val RETAINED_STATE_BASE_BYTES = 512L
private const val RETAINED_STATE_OVERHEAD_MULTIPLIER = 8L
private const val MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT = 3L

enum class QueryRetainedResultFailure {
    EXECUTION_REJECTED,
    BASIS_MISMATCH,
    INCONSISTENT_COVERAGE,
    UNKNOWN_ROW,
    DUPLICATE_ROW,
}

enum class QueryMembershipFailure {
    INCOMPLETE
}

/** A proof bound to the exact retained input whose entire membership is known. */
class QueryCompleteMembership private constructor(val source: QueryRetainedResult.Symbols) {
    val lease: SemanticReadAuthority
        get() = source.lease

    val symbols: List<QuerySymbol>
        get() = source.symbols

    companion object {
        fun from(result: QueryRetainedResult.Symbols): Refinement<QueryCompleteMembership, QueryMembershipFailure> =
            if (result.coverage !is QueryCoverage.Complete) {
                Refinement.Rejected(QueryMembershipFailure.INCOMPLETE)
            } else if (
                result.failures.isNotEmpty() || result.omissions.isNotEmpty() || result.producerProgress != null
            ) {
                Refinement.Rejected(QueryMembershipFailure.INCOMPLETE)
            } else {
                Refinement.Refined(QueryCompleteMembership(result))
            }
    }
}

/** Detached, immutable semantic rows from one read basis. Row kind survives retention and selection. */
sealed class QueryRetainedResult
protected constructor(
    val lease: SemanticReadAuthority,
    itemFailures: List<QueryItemFailure>,
    relationOmissions: List<QueryRelationOmission>,
    traversalObservations: List<QueryWalkObservation>,
    resultCoverage: QueryCoverage,
    val producerProgress: QueryContinuationState?,
) {
    private val itemFailures = Collections.unmodifiableList(itemFailures.toList())
    private val relationOmissions = Collections.unmodifiableList(relationOmissions.toList())
    private val traversalObservations = Collections.unmodifiableList(traversalObservations.toList())
    private val resultCoverage = resultCoverage.copyCoverage()

    val failures: List<QueryItemFailure>
        get() = itemFailures

    val omissions: List<QueryRelationOmission>
        get() = relationOmissions

    val walkObservations: List<QueryWalkObservation>
        get() = traversalObservations

    val coverage: QueryCoverage
        get() = resultCoverage

    /** Conservative detached-state accounting includes source text and unfinished producer work. */
    abstract val rowCount: Int

    abstract val retainedBytes: Long

    abstract fun selectRows(indices: List<Int>): Refinement<QueryRetainedResult, QueryRetainedResultFailure>

    fun completeMembership(): Refinement<QueryCompleteMembership, QueryMembershipFailure> =
        if (this is Symbols) {
            QueryCompleteMembership.from(this)
        } else {
            Refinement.Rejected(QueryMembershipFailure.INCOMPLETE)
        }

    /** Select original proven rows; excluded rows remain unchecked rather than proven nonmatches. */
    protected fun validateIndices(indices: List<Int>): QueryRetainedResultFailure? {
        if (indices.any { it !in 0 until rowCount }) return QueryRetainedResultFailure.UNKNOWN_ROW
        if (indices.distinct().size != indices.size) return QueryRetainedResultFailure.DUPLICATE_ROW
        return null
    }

    protected fun selectionCoverage(selectedCount: Int): Pair<QueryCoverage, QueryContinuationState?> {
        val coverage =
            if (selectedCount == rowCount) {
                resultCoverage.copyCoverage()
            } else {
                val prior = (resultCoverage as? QueryCoverage.Qualified)?.limitations.orEmpty()
                when (
                    val refined =
                        QueryCoverage.Qualified.create(
                            QueryCount.parse(selectedCount).refinedCount(),
                            prior.toSet() + QueryLimitation.ROW_SELECTION_INCOMPLETE,
                        )
                ) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> error("A partial row selection lost its limitation")
                }
            }
        val progress =
            if (coverage is QueryCoverage.Qualified && producerProgress == null) {
                QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)
            } else {
                producerProgress
            }
        return coverage to progress
    }

    class Symbols
    internal constructor(
        lease: SemanticReadAuthority,
        rows: List<QuerySymbol>,
        failures: List<QueryItemFailure>,
        omissions: List<QueryRelationOmission>,
        observations: List<QueryWalkObservation>,
        coverage: QueryCoverage,
        progress: QueryContinuationState?,
    ) : QueryRetainedResult(lease, failures, omissions, observations, coverage, progress) {
        private val rows = Collections.unmodifiableList(rows.map(QuerySymbol::detached))

        val symbols: List<QuerySymbol>
            get() = rows

        override val rowCount: Int
            get() = rows.size

        override val retainedBytes: Long =
            retainedStorageBytes(rows, failures, omissions, observations, coverage, progress, lease)

        override fun selectRows(indices: List<Int>): Refinement<Symbols, QueryRetainedResultFailure> {
            validateIndices(indices)?.let {
                return Refinement.Rejected(it)
            }
            val (selectedCoverage, selectedProgress) = selectionCoverage(indices.size)
            return Refinement.Refined(
                Symbols(
                    lease,
                    indices.map(rows::get),
                    failures,
                    omissions,
                    walkObservations,
                    selectedCoverage,
                    selectedProgress,
                )
            )
        }
    }

    class Bindings
    internal constructor(
        lease: SemanticReadAuthority,
        rows: List<QueryBindingRow>,
        failures: List<QueryItemFailure>,
        omissions: List<QueryRelationOmission>,
        observations: List<QueryWalkObservation>,
        coverage: QueryCoverage,
        progress: QueryContinuationState?,
    ) : QueryRetainedResult(lease, failures, omissions, observations, coverage, progress) {
        private val rows = Collections.unmodifiableList(rows.map(QueryBindingRow::detached))

        val bindingRows: List<QueryBindingRow>
            get() = rows

        override val rowCount: Int
            get() = rows.size

        override val retainedBytes: Long =
            retainedStorageBytes(
                    rows.flatMap { listOf(it.left.value.symbol, it.right.value.symbol) },
                    failures,
                    omissions,
                    observations,
                    coverage,
                    progress,
                    lease,
                )
                .saturatedAdd(rows.size.toLong().saturatedMultiply(RETAINED_STATE_BASE_BYTES))

        override fun selectRows(indices: List<Int>): Refinement<Bindings, QueryRetainedResultFailure> {
            validateIndices(indices)?.let {
                return Refinement.Rejected(it)
            }
            val (selectedCoverage, selectedProgress) = selectionCoverage(indices.size)
            return Refinement.Refined(
                Bindings(
                    lease,
                    indices.map(rows::get),
                    failures,
                    omissions,
                    walkObservations,
                    selectedCoverage,
                    selectedProgress,
                )
            )
        }
    }

    companion object {
        fun capture(
            lease: SemanticReadAuthority,
            execution: QueryExecutionResult,
        ): Refinement<QueryRetainedResult, QueryRetainedResultFailure> {
            val result: QueryResult
            val coverage: QueryCoverage
            val progress: QueryContinuationState?
            when (execution) {
                is QueryExecutionResult.Complete -> {
                    if (execution.result.failures.isNotEmpty() || execution.result.omissions.isNotEmpty()) {
                        return Refinement.Rejected(QueryRetainedResultFailure.INCONSISTENT_COVERAGE)
                    }
                    if (execution.result.walkObservations.any { it.coverage.isTerminallyIncomplete() }) {
                        return Refinement.Rejected(QueryRetainedResultFailure.INCONSISTENT_COVERAGE)
                    }
                    result = execution.result
                    coverage = execution.coverage
                    progress = null
                }
                is QueryExecutionResult.Qualified -> {
                    result = execution.result
                    coverage = execution.coverage
                    progress = execution.continuation
                }
                is QueryExecutionResult.Rejected ->
                    return Refinement.Rejected(QueryRetainedResultFailure.EXECUTION_REJECTED)
            }
            if (
                result.hasForeignBasis(lease) ||
                    (progress as? QueryContinuationState.Resumable)?.checkpoint?.lease?.let { it != lease } == true
            ) {
                return Refinement.Rejected(QueryRetainedResultFailure.BASIS_MISMATCH)
            }
            return Refinement.Refined(captureRows(lease, result, coverage, progress))
        }

        private fun captureRows(
            lease: SemanticReadAuthority,
            result: QueryResult,
            coverage: QueryCoverage,
            progress: QueryContinuationState?,
        ): QueryRetainedResult =
            when (val rows = result.rows) {
                is QueryRows.Symbols ->
                    Symbols(
                        lease,
                        rows.values,
                        result.failures,
                        result.omissions,
                        result.walkObservations,
                        coverage,
                        progress,
                    )
                is QueryRows.Bindings ->
                    Bindings(
                        lease,
                        rows.values,
                        result.failures,
                        result.omissions,
                        result.walkObservations,
                        coverage,
                        progress,
                    )
            }
    }
}

private fun Refinement<QueryCount, QueryCountFailure>.refinedCount(): QueryCount =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("A collection size cannot be negative")
    }

private fun QueryCoverage.copyCoverage(): QueryCoverage =
    when (this) {
        is QueryCoverage.Complete -> copy()
        is QueryCoverage.Qualified ->
            when (val copied = QueryCoverage.Qualified.create(knownMinimum, limitations.toSet())) {
                is Refinement.Refined -> copied.value
                is Refinement.Rejected -> error("A qualified query result lost its limitations")
            }
    }

private fun QuerySymbol.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    selector.lease != lease ||
        connections.any { fact ->
            fact.authority != lease.identity ||
                fact.subject.lease != lease ||
                fact.source.lease != lease ||
                fact.target.lease != lease
        }

private fun QueryResult.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    (when (val resultRows = rows) {
        is QueryRows.Symbols -> resultRows.values.any { it.hasForeignBasis(lease) }
        is QueryRows.Bindings ->
            resultRows.values.any { row ->
                row.left.value.symbol.hasForeignBasis(lease) || row.right.value.symbol.hasForeignBasis(lease)
            }
    }) ||
        failures.any { it.hasForeignBasis(lease) } ||
        omissions.any { it.subject.lease != lease } ||
        walkObservations.any { it.subject.lease != lease }

private fun QueryWalkCoverage.isTerminallyIncomplete(): Boolean =
    this is QueryWalkCoverage.TerminalIncomplete ||
        (this is QueryWalkCoverage.Resumable &&
            io.github.amichne.kast.traversal.contract.TraversalLimitation.ONE_HOP_INCOMPLETE in limitations)

private fun QueryItemFailure.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    when (this) {
        is QueryItemFailure.Refinement -> candidate.lease != lease
        is QueryItemFailure.Visibility -> selector.lease != lease
        is QueryItemFailure.ExactReference -> selector.lease != lease
        is QueryItemFailure.PredicateUnproven -> selector.lease != lease
        is QueryItemFailure.Source -> selector.lease != lease
        is QueryItemFailure.Relation -> selector.lease != lease
        is QueryItemFailure.Walk -> selector.lease != lease
    }

private fun retainedStorageBytes(
    rows: List<QuerySymbol>,
    failures: List<QueryItemFailure>,
    omissions: List<QueryRelationOmission>,
    walkObservations: List<QueryWalkObservation>,
    coverage: QueryCoverage,
    progress: QueryContinuationState?,
    lease: SemanticReadAuthority,
): Long {
    val sourceText =
        rows.fold(0L) { size, row ->
            val returned = row.source as? QuerySymbolSource.Returned
            size.saturatedAdd(returned?.value?.text?.utf8UpperBound() ?: 0L)
        }
    val relationEvidence =
        rows.fold(0L) { size, row ->
            row.connections.fold(size) { total, fact ->
                total.saturatedAdd(fact.canonicalProjection().utf8UpperBound())
            }
        }
    val checkpoint = (progress as? QueryContinuationState.Resumable)?.checkpoint
    val priorRetainedSource = (checkpoint?.plan as? AdmittedQueryPlan.Retained)?.source?.retainedBytes ?: 0L
    return RETAINED_STATE_BASE_BYTES.saturatedAdd(rows.toString().utf8UpperBound())
        .saturatedAdd(failures.toString().utf8UpperBound())
        .saturatedAdd(omissions.toString().utf8UpperBound())
        .saturatedAdd(walkObservations.toString().utf8UpperBound())
        .saturatedAdd(coverage.toString().utf8UpperBound())
        .saturatedAdd(lease.toString().utf8UpperBound())
        .saturatedAdd(sourceText)
        .saturatedAdd(relationEvidence)
        .saturatedAdd(checkpoint?.retainedBytes ?: 0L)
        .saturatedAdd(priorRetainedSource)
        .saturatedMultiply(RETAINED_STATE_OVERHEAD_MULTIPLIER)
}

private fun String.utf8UpperBound(): Long = length.toLong().saturatedMultiply(MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT)

private fun Long.saturatedAdd(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Long.saturatedMultiply(other: Long): Long =
    if (this < 0L || other < 0L || this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other
