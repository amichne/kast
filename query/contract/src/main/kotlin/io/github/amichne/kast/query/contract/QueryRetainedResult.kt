package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.util.Collections

/** Detached, immutable semantic rows from one read basis. Row kind survives retention and selection. */
sealed class QueryRetainedResult
protected constructor(
    val lease: SemanticReadAuthority,
    itemFailures: List<QueryItemFailure>,
    relationOmissions: List<QueryRelationOmission>,
    traversalObservations: List<QueryWalkObservation>,
    resultCoverage: QueryCoverage,
    val producerProgress: QueryContinuationState?,
    referenceObservations: List<RelationReferenceOccurrence>,
    discoveryObservations: List<QueryDiscoveryObservation>,
    relationObservations: List<QueryRelationObservation>,
) {
    private val itemFailures = Collections.unmodifiableList(itemFailures.toList())
    private val relationOmissions = Collections.unmodifiableList(relationOmissions.toList())
    private val traversalObservations = Collections.unmodifiableList(traversalObservations.toList())
    private val resultCoverage = resultCoverage.copyCoverage()
    val referenceObservations = Collections.unmodifiableList(referenceObservations.toList())
    val discoveryObservations = Collections.unmodifiableList(discoveryObservations.toList())
    val relationObservations = Collections.unmodifiableList(relationObservations.toList())

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
        references: List<RelationReferenceOccurrence> = emptyList(),
        discoveries: List<QueryDiscoveryObservation> = emptyList(),
        relations: List<QueryRelationObservation> = emptyList(),
    ) :
        QueryRetainedResult(
            lease,
            failures,
            omissions,
            observations,
            coverage,
            progress,
            references,
            discoveries,
            relations,
        ) {
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
                relations,
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
                    relationObservations,
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
        references: List<RelationReferenceOccurrence> = emptyList(),
        discoveries: List<QueryDiscoveryObservation> = emptyList(),
        relations: List<QueryRelationObservation> = emptyList(),
    ) :
        QueryRetainedResult(
            lease,
            failures,
            omissions,
            observations,
            coverage,
            progress,
            references,
            discoveries,
            relations,
        ) {
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
                    relations,
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
                    relationObservations,
                )
            )
        }
    }

    class ValuePaths
    internal constructor(
        lease: SemanticReadAuthority,
        val rows: QueryRows.ValuePaths,
        failures: List<QueryItemFailure>,
        omissions: List<QueryRelationOmission>,
        observations: List<QueryWalkObservation>,
        coverage: QueryCoverage,
        progress: QueryContinuationState?,
        references: List<RelationReferenceOccurrence> = emptyList(),
        discoveries: List<QueryDiscoveryObservation> = emptyList(),
        relations: List<QueryRelationObservation> = emptyList(),
    ) :
        QueryRetainedResult(
            lease,
            failures,
            omissions,
            observations,
            coverage,
            progress,
            references,
            discoveries,
            relations,
        ) {
        val valuePaths: List<QueryImpactPath>
            get() = rows.values

        override val rowCount: Int
            get() = rows.values.size

        override val retainedBytes: Long
            get() = retainedBytes(QueryImpactRetainedGraph())

        /** A request-local graph charges shared original ledger objects once across admitted pages. */
        fun retainedBytes(graph: QueryImpactRetainedGraph): Long = valuePathRetainedBytes(this, graph)

        override fun selectRows(indices: List<Int>): Refinement<ValuePaths, QueryRetainedResultFailure> {
            validateIndices(indices)?.let {
                return Refinement.Rejected(it)
            }
            val (selectedCoverage, selectedProgress) = selectionCoverage(indices.size)
            val selected =
                when (val admitted = rows.selectRows(indices)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            return Refinement.Refined(
                ValuePaths(
                    lease,
                    selected,
                    failures,
                    omissions,
                    walkObservations,
                    selectedCoverage,
                    selectedProgress,
                    referenceObservations,
                    discoveryObservations,
                    relationObservations,
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
        references: List<RelationReferenceOccurrence> = emptyList(),
        discoveries: List<QueryDiscoveryObservation> = emptyList(),
        relations: List<QueryRelationObservation> = emptyList(),
    ) :
        QueryRetainedResult(
            lease,
            failures,
            omissions,
            observations,
            coverage,
            progress,
            references,
            discoveries,
            relations,
        ) {
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
                    relations,
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
                    relationObservations,
                )
            )
        }
    }

    companion object {
        fun captureInvestigation(
            lease: SemanticReadAuthority,
            execution: QueryExecutionResult,
        ): Refinement<QueryRetainedResult, QueryRetainedResultFailure> =
            captureRetainedQueryResult(lease, execution, QueryRetainedCaptureRows.ORIGINAL_INVESTIGATION)

        fun capture(
            lease: SemanticReadAuthority,
            execution: QueryExecutionResult,
        ): Refinement<QueryRetainedResult, QueryRetainedResultFailure> = captureRetainedQueryResult(lease, execution)
    }
}
