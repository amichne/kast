package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult

/** Completeness remains a proof over pending work, emitted facts, and detailed qualifications together. */
internal fun QueryExecutionState.completePage(
    result: QueryResult,
    tasks: ArrayDeque<PipelineTask>,
    emittedBefore: QueryCount,
    pageCount: Int,
    continuation: (QueryCount) -> QueryContinuationState,
): QueryExecutionResult {
    if (emittedBefore.value > Int.MAX_VALUE - pageCount)
        return QueryExecutionResult.Rejected(QueryExecutionRejection.BUDGET_REJECTED)
    val count = (emittedBefore.value + pageCount).queryCount()
    if (completedWithoutMissingEvidence(tasks, result))
        return QueryExecutionResult.Complete(result, QueryCoverage.Complete(count))
    if (limitations.isEmpty()) limit(QueryLimitation.WORK_LIMIT_REACHED)
    val coverage =
        when (val admitted = QueryCoverage.Qualified.create(count, limitations)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
    return QueryExecutionResult.Qualified(result, coverage, continuation(count))
}
