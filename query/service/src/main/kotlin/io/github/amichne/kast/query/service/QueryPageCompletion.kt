package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryInvestigationCompletion
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
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
    val originalCompletion = originalInvestigationCompletion(result, tasks, count)
    return QueryExecutionResult.Qualified(
        result,
        coverage,
        continuation(count),
        investigationCompletion = originalCompletion,
    )
}

internal fun QueryExecutionState.completedWithoutMissingEvidence(
    tasks: Collection<PipelineTask>,
    result: QueryResult,
): Boolean {
    if (tasks.isNotEmpty() || upstreamLimitations.isNotEmpty()) return false
    if (result.failures.isNotEmpty() || result.omissions.isNotEmpty()) return false
    if (result.walkObservations.any { it.coverage !is QueryWalkCoverage.Complete }) return false
    // A clock read after the final successful effect cannot make completed work incomplete.
    return limitations.isEmpty() || limitations == setOf(QueryLimitation.TIME_LIMIT_REACHED)
}

/** Only exhausted original work may discharge the page-local selection qualification. */
private fun QueryExecutionState.originalInvestigationCompletion(
    result: QueryResult,
    tasks: Collection<PipelineTask>,
    count: QueryCount,
): QueryInvestigationCompletion {
    val absent = QueryInvestigationCompletion.NotEstablished
    if (!request.plan.preservesOriginalInvestigation()) return absent
    if (tasks.isNotEmpty() || upstreamLimitations.isNotEmpty()) return absent
    if (limitations.any { it != QueryLimitation.ROW_SELECTION_INCOMPLETE && it != QueryLimitation.TIME_LIMIT_REACHED })
        return absent
    val rows = result.rows as? QueryRows.ValuePaths ?: return absent
    val accounting = rows.accounting as? QueryValuePathAccounting.Investigated ?: return absent
    if (accounting.ledger.paths.size != count.value) return absent
    val original =
        when (val admitted = QueryRows.ValuePaths.fromInvestigation(accounting.ledger)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return absent
        }
    return when (
        val execution =
            QueryExecutionResult.Complete.create(
                result.copy(rows = original),
                QueryCoverage.Complete(count),
            )
    ) {
        is QueryExecutionResult.Complete -> QueryInvestigationCompletion.Established.from(execution)
        is QueryExecutionResult.Rejection,
        is QueryExecutionResult.Qualified -> absent
    }
}

/** A selected or qualified retained source cannot inherit original investigation completion. */
internal fun AdmittedQueryPlan.preservesOriginalInvestigation(): Boolean =
    when (this) {
        is AdmittedQueryPlan.Impact -> true
        is AdmittedQueryPlan.Retained -> {
            val retained = source as? QueryRetainedResult.ValuePaths
            retained != null &&
                retained.coverage is QueryCoverage.Complete &&
                retained.rows.accountingStatus == QueryValuePathAccountingStatus.Conserved
        }
        else -> false
    }
