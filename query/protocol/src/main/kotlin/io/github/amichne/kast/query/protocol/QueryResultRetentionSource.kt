package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.contract.QueryRetainedRowOrdinal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** A pending checkpoint owns unfinished routes; only a finalized ledger owns original path ordinals. */
internal class QueryResultRetentionSource
private constructor(
    private val execution: QueryExecutionResult,
    private val result: QueryResult,
    val scope: QueryResultRetentionScope,
) {
    fun capture(lease: SemanticReadAuthority): Refinement<QueryRetainedResult, QueryRetainedResultFailure> =
        when (scope) {
            QueryResultRetentionScope.ORIGINAL_INVESTIGATION ->
                QueryRetainedResult.captureInvestigation(lease, execution)
            QueryResultRetentionScope.PRESENTED,
            QueryResultRetentionScope.PENDING_IMPACT -> QueryRetainedResult.capture(lease, execution)
        }

    fun ordinals(retained: QueryRetainedResult): Refinement<List<QueryRetainedRowOrdinal>, QueryRetainedResultFailure> {
        if (scope == QueryResultRetentionScope.ORIGINAL_INVESTIGATION) {
            val original = retained as? QueryRetainedResult.ValuePaths ?: return inconsistent()
            val selected = result.rows as? QueryRows.ValuePaths ?: return inconsistent()
            return original.originalOrdinals(selected)
        }
        val ordinals = mutableListOf<QueryRetainedRowOrdinal>()
        for (index in 0 until retained.rowCount) when (
            val admitted = QueryRetainedRowOrdinal.admit(index, retained.rowCount)
        ) {
            is Refinement.Refined -> ordinals += admitted.value
            is Refinement.Rejected -> return admitted
        }
        return Refinement.Refined(ordinals)
    }

    companion object {
        fun admit(
            from: QueryFromDocument,
            execution: QueryExecutionResult,
        ): Refinement<QueryResultRetentionSource, QueryRetainedResultFailure> {
            val result =
                when (execution) {
                    is QueryExecutionResult.Complete -> execution.result
                    is QueryExecutionResult.Qualified -> execution.result
                    is QueryExecutionResult.Rejection ->
                        return Refinement.Rejected(QueryRetainedResultFailure.EXECUTION_REJECTED)
                }
            if (from !is QueryFromDocument.Impact)
                return Refinement.Refined(
                    QueryResultRetentionSource(execution, result, QueryResultRetentionScope.PRESENTED)
                )
            val rows = result.rows as? QueryRows.ValuePaths ?: return inconsistent()
            val scope =
                when (rows.accounting) {
                    is QueryValuePathAccounting.Investigated -> QueryResultRetentionScope.ORIGINAL_INVESTIGATION
                    QueryValuePathAccounting.EvidenceOnly -> {
                        if (
                            execution !is QueryExecutionResult.Qualified ||
                                execution.continuation !is QueryContinuationState.Resumable ||
                                rows.values.isNotEmpty()
                        )
                            return inconsistent()
                        QueryResultRetentionScope.PENDING_IMPACT
                    }
                }
            return Refinement.Refined(QueryResultRetentionSource(execution, result, scope))
        }

        private fun inconsistent() = Refinement.Rejected(QueryRetainedResultFailure.INCONSISTENT_COVERAGE)
    }
}
