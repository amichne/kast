package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

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

/** Detached, immutable semantic rows from one read basis. Presentation fields are selected later. */
class QueryRetainedResult
private constructor(
    val lease: SemanticReadAuthority,
    private val rows: List<QuerySymbol>,
    private val itemFailures: List<QueryItemFailure>,
    private val relationOmissions: List<QueryRelationOmission>,
    private val resultCoverage: QueryCoverage,
    val producerProgress: QueryContinuationState?,
) {
    val symbols: List<QuerySymbol>
        get() = rows.map { row -> row.copy(connections = row.connections.toList()) }

    val failures: List<QueryItemFailure>
        get() = itemFailures.toList()

    val omissions: List<QueryRelationOmission>
        get() = relationOmissions.toList()

    val coverage: QueryCoverage
        get() = resultCoverage.copyCoverage()

    /** Conservative detached-state accounting includes source text and unfinished producer work. */
    val retainedBytes: Long =
        retainedStorageBytes(rows, itemFailures, relationOmissions, resultCoverage, producerProgress, lease)

    /** Select original proven rows; excluded rows remain unchecked rather than proven nonmatches. */
    fun selectRows(indices: List<Int>): Refinement<QueryRetainedResult, QueryRetainedResultFailure> {
        if (indices.any { it !in rows.indices }) return Refinement.Rejected(QueryRetainedResultFailure.UNKNOWN_ROW)
        if (indices.distinct().size != indices.size)
            return Refinement.Rejected(QueryRetainedResultFailure.DUPLICATE_ROW)
        val selected = indices.map(rows::get)
        val coverage =
            if (selected.size == rows.size) {
                resultCoverage.copyCoverage()
            } else {
                val prior = (resultCoverage as? QueryCoverage.Qualified)?.limitations.orEmpty()
                when (
                    val refined =
                        QueryCoverage.Qualified.create(
                            QueryCount.parse(selected.size).refinedCount(),
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
        return Refinement.Refined(
            QueryRetainedResult(lease, selected, itemFailures, relationOmissions, coverage, progress)
        )
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
            val symbols = result.items
            if (
                result.hasForeignBasis(lease) ||
                    (progress as? QueryContinuationState.Resumable)?.checkpoint?.lease?.let { it != lease } == true
            ) {
                return Refinement.Rejected(QueryRetainedResultFailure.BASIS_MISMATCH)
            }
            return Refinement.Refined(
                QueryRetainedResult(
                    lease,
                    symbols.map { it.copy(connections = it.connections.toList()) },
                    result.failures.toList(),
                    result.omissions.toList(),
                    coverage.copyCoverage(),
                    progress,
                )
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
    items.any { it.hasForeignBasis(lease) } ||
        failures.any { it.hasForeignBasis(lease) } ||
        omissions.any { it.subject.lease != lease }

private fun QueryItemFailure.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    when (this) {
        is QueryItemFailure.Refinement -> candidate.lease != lease
        is QueryItemFailure.Visibility -> selector.lease != lease
        is QueryItemFailure.ExactReference -> selector.lease != lease
        is QueryItemFailure.PredicateUnproven -> selector.lease != lease
        is QueryItemFailure.Source -> selector.lease != lease
        is QueryItemFailure.Relation -> selector.lease != lease
    }

private fun retainedStorageBytes(
    rows: List<QuerySymbol>,
    failures: List<QueryItemFailure>,
    omissions: List<QueryRelationOmission>,
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
