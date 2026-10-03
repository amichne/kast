package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal fun captureRetainedQueryResult(
    lease: SemanticReadAuthority,
    execution: QueryExecutionResult,
): Refinement<QueryRetainedResult, QueryRetainedResultFailure> {
    val result: QueryResult
    val coverage: QueryCoverage
    val progress: QueryContinuationState?
    when (execution) {
        is QueryExecutionResult.Complete -> {
            if (execution.result.hasUnresolvedRequiredEvidence()) {
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
        is QueryExecutionResult.Rejection -> return Refinement.Rejected(QueryRetainedResultFailure.EXECUTION_REJECTED)
    }
    if (
        result.hasForeignBasis(lease) ||
            (progress as? QueryContinuationState.Resumable)?.checkpoint?.lease?.let { it != lease } == true
    ) {
        return Refinement.Rejected(QueryRetainedResultFailure.BASIS_MISMATCH)
    }
    return captureRows(lease, result, coverage, progress)
}

private fun captureRows(
    lease: SemanticReadAuthority,
    result: QueryResult,
    coverage: QueryCoverage,
    progress: QueryContinuationState?,
): Refinement<QueryRetainedResult, QueryRetainedResultFailure> =
    when (val rows = result.rows) {
        is QueryRows.ImpactWitness -> Refinement.Rejected(QueryRetainedResultFailure.PRESENTATION_ONLY_ROWS)
        is QueryRows.Symbols ->
            Refinement.Refined(
                QueryRetainedResult.Symbols(
                    lease,
                    rows.values,
                    result.failures,
                    result.omissions,
                    result.walkObservations,
                    coverage,
                    progress,
                    result.referenceObservations,
                    result.discoveryObservations,
                    result.relationObservations,
                )
            )
        is QueryRows.Occurrences ->
            Refinement.Refined(
                QueryRetainedResult.Occurrences(
                    lease,
                    rows.values,
                    result.failures,
                    result.omissions,
                    result.walkObservations,
                    coverage,
                    progress,
                    result.referenceObservations,
                    result.discoveryObservations,
                    result.relationObservations,
                )
            )
        is QueryRows.ValuePaths -> Refinement.Refined(captureValuePaths(lease, rows, result, coverage, progress))
        is QueryRows.Bindings ->
            Refinement.Refined(
                QueryRetainedResult.Bindings(
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
                    result.relationObservations,
                )
            )
    }

private fun captureValuePaths(
    lease: SemanticReadAuthority,
    rows: QueryRows.ValuePaths,
    result: QueryResult,
    coverage: QueryCoverage,
    progress: QueryContinuationState?,
): QueryRetainedResult.ValuePaths =
    QueryRetainedResult.ValuePaths(
        lease,
        rows,
        result.failures,
        result.omissions,
        result.walkObservations,
        coverage,
        progress,
        result.referenceObservations,
        result.discoveryObservations,
        result.relationObservations,
    )
