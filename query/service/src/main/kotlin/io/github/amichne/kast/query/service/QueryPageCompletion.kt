package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.QueryWalkCoverage
import io.github.amichne.kast.query.contract.accountingStatus

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
    when (val rows = result.rows) {
        is QueryRows.ValuePaths ->
            when (rows.accountingStatus) {
                QueryValuePathAccountingStatus.Conserved -> Unit
                QueryValuePathAccountingStatus.EvidenceOnly,
                is QueryValuePathAccountingStatus.Unresolved -> limit(QueryLimitation.IMPACT_COVERAGE_UNPROVEN)
                is QueryValuePathAccountingStatus.SelectedSubset -> limit(QueryLimitation.ROW_SELECTION_INCOMPLETE)
            }
        is QueryRows.ImpactWitness -> limit(QueryLimitation.ROW_SELECTION_INCOMPLETE)
        is QueryRows.Symbols,
        is QueryRows.Occurrences,
        is QueryRows.Bindings -> Unit
    }
    if (completedWithoutMissingEvidence(tasks, result))
        return QueryExecutionResult.Complete.create(result, QueryCoverage.Complete(count))
    if (limitations.isEmpty()) limit(QueryLimitation.WORK_LIMIT_REACHED)
    val coverage =
        when (val admitted = QueryCoverage.Qualified.create(count, limitations)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION)
        }
    return QueryExecutionResult.Qualified(result, coverage, continuation(count))
}

internal fun QueryExecutionState.completedWithoutMissingEvidence(
    tasks: Collection<PipelineTask>,
    result: io.github.amichne.kast.query.contract.QueryResult,
): Boolean {
    if (tasks.isNotEmpty() || upstreamLimitations.isNotEmpty()) return false
    if (result.failures.isNotEmpty() || result.omissions.isNotEmpty()) return false
    if (result.walkObservations.any { it.coverage !is QueryWalkCoverage.Complete }) return false
    // A clock read after the final successful effect cannot make completed work incomplete.
    return limitations.isEmpty() || limitations == setOf(QueryLimitation.TIME_LIMIT_REACHED)
}
