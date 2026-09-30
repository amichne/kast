package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.Collections

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
    referenceObservations: List<io.github.amichne.kast.relation.contract.RelationReferenceOccurrence>,
    discoveryObservations: List<QueryDiscoveryObservation>,
) {
    private val itemFailures = Collections.unmodifiableList(itemFailures.toList())
    private val relationOmissions = Collections.unmodifiableList(relationOmissions.toList())
    private val traversalObservations = Collections.unmodifiableList(traversalObservations.toList())
    private val resultCoverage = resultCoverage.copyCoverage()
    val referenceObservations = Collections.unmodifiableList(referenceObservations.toList())
    val discoveryObservations = Collections.unmodifiableList(discoveryObservations.toList())

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
        references: List<io.github.amichne.kast.relation.contract.RelationReferenceOccurrence> = emptyList(),
        discoveries: List<QueryDiscoveryObservation> = emptyList(),
    ) : QueryRetainedResult(lease, failures, omissions, observations, coverage, progress, references, discoveries) {
        private val rows = Collections.unmodifiableList(rows.map(QuerySymbol::detached))

        val symbols: List<QuerySymbol>
            get() = rows

        override val rowCount: Int
            get() = rows.size

        override val retainedBytes: Long =
            retainedStorageBytes(
                rows,
                failures,
                omissions,
                observations,
                coverage,
                progress,
                lease,
                references,
                discoveries,
            )

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
                    referenceObservations,
                    discoveryObservations,
                )
            )
        }
    }

    class Occurrences
    internal constructor(
        lease: SemanticReadAuthority,
        rows: List<QueryOccurrence>,
        failures: List<QueryItemFailure>,
        omissions: List<QueryRelationOmission>,
        observations: List<QueryWalkObservation>,
        coverage: QueryCoverage,
        progress: QueryContinuationState?,
        references: List<io.github.amichne.kast.relation.contract.RelationReferenceOccurrence> = emptyList(),
        discoveries: List<QueryDiscoveryObservation> = emptyList(),
    ) : QueryRetainedResult(lease, failures, omissions, observations, coverage, progress, references, discoveries) {
        private val rows = Collections.unmodifiableList(rows.map(QueryOccurrence::detached))
        val occurrences: List<QueryOccurrence>
            get() = rows

        override val rowCount: Int
            get() = rows.size

        override val retainedBytes: Long =
            retainedStorageBytes(
                    rows.filterIsInstance<QueryOccurrence.Declaration>().map { it.symbol },
                    failures,
                    omissions,
                    observations,
                    coverage,
                    progress,
                    lease,
                    references,
                    discoveries,
                )
                .saturatedAdd(
                    rows.sumOf { occurrence ->
                        when (occurrence) {
                            is QueryOccurrence.Reference -> occurrence.value.retainedBytes.saturatedMultiply(8L)
                            is QueryOccurrence.Declaration ->
                                occurrence.fact.canonicalProjection().utf8UpperBound().saturatedMultiply(8L)
                        }
                    }
                )

        override fun selectRows(indices: List<Int>): Refinement<Occurrences, QueryRetainedResultFailure> {
            validateIndices(indices)?.let {
                return Refinement.Rejected(it)
            }
            val (selectedCoverage, selectedProgress) = selectionCoverage(indices.size)
            return Refinement.Refined(
                Occurrences(
                    lease,
                    indices.map(rows::get),
                    failures,
                    omissions,
                    walkObservations,
                    selectedCoverage,
                    selectedProgress,
                    referenceObservations,
                    discoveryObservations,
                )
            )
        }
    }

    class Bindings
    internal constructor(
        lease: SemanticReadAuthority,
        rows: List<QueryBindingRow>,
        val mode: QueryJoinMode.Inner,
        failures: List<QueryItemFailure>,
        omissions: List<QueryRelationOmission>,
        observations: List<QueryWalkObservation>,
        coverage: QueryCoverage,
        progress: QueryContinuationState?,
        references: List<io.github.amichne.kast.relation.contract.RelationReferenceOccurrence> = emptyList(),
        discoveries: List<QueryDiscoveryObservation> = emptyList(),
    ) : QueryRetainedResult(lease, failures, omissions, observations, coverage, progress, references, discoveries) {
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
                    references,
                    discoveries,
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
                    mode,
                    failures,
                    omissions,
                    walkObservations,
                    selectedCoverage,
                    selectedProgress,
                    referenceObservations,
                    discoveryObservations,
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
                        result.referenceObservations,
                        result.discoveryObservations,
                    )
                is QueryRows.Occurrences ->
                    Occurrences(
                        lease,
                        rows.values,
                        result.failures,
                        result.omissions,
                        result.walkObservations,
                        coverage,
                        progress,
                        result.referenceObservations,
                        result.discoveryObservations,
                    )
                is QueryRows.Bindings ->
                    Bindings(
                        lease,
                        rows.values,
                        rows.mode,
                        result.failures,
                        result.omissions,
                        result.walkObservations,
                        coverage,
                        progress,
                        result.referenceObservations,
                        result.discoveryObservations,
                    )
            }
    }
}
