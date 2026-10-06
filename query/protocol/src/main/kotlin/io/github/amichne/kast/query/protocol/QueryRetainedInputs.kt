package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal fun QueryRunRequest.Run.resultInputs(): List<QueryFromDocument.Result> = buildList {
    (from as? QueryFromDocument.Result)?.let(::add)
    steps.values.forEach { step ->
        when (step) {
            is QueryStepDocument.Concat -> (step.input as? QueryFromDocument.Result)?.let(::add)
            is QueryStepDocument.Join -> (step.right as? QueryFromDocument.Result)?.let(::add)
            is QueryStepDocument.Intersect -> add(step.right)
            is QueryStepDocument.Union -> add(step.right)
            is QueryStepDocument.Difference -> add(step.right)
            else -> Unit
        }
    }
}

internal fun QueryResultRestoration.Restored.selectRows(
    selectedIds: List<QueryResultRowReference>?
): QueryRetainedResult? {
    if (selectedIds == null) return result
    if (selectedIds.distinct().size != selectedIds.size) return null
    val positions = rowIds.withIndex().associate { (position, rowId) -> rowId to position }
    val indices = selectedIds.map { positions[it] ?: return null }
    return when (val selected = result.selectRows(indices)) {
        is Refinement.Refined -> selected.value
        is Refinement.Rejected -> null
    }
}

/** Restores exactly the retained inputs protected by the active execution claim. */
internal class QueryRetainedInputAdmission(private val state: QueryStateStore) {
    fun restore(
        request: QueryRunRequest.Run,
        lease: SemanticReadAuthority,
        publicationOwner: QueryExecutionClaim?,
    ): Refinement<Map<QueryFromDocument.Result, QueryRetainedResult>, QueryRunRejection> {
        val retained = linkedMapOf<QueryFromDocument.Result, QueryRetainedResult>()
        for (source in request.resultInputs()) {
            if (source in retained) continue
            when (val restored = state.restoreResult(source.reference, lease, publicationOwner)) {
                is QueryResultRestoration.Restored -> {
                    val selected =
                        restored.selectRows(source.rowIds?.values)
                            ?: return rejectedResultInput(QueryExecutionRejectionDocument.RESULT_ROW_UNAVAILABLE)
                    retained[source] = selected
                }
                QueryResultRestoration.Unavailable ->
                    return rejectedResultInput(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE)
                QueryResultRestoration.StaleBasis ->
                    return rejectedResultInput(QueryExecutionRejectionDocument.RESULT_STALE_BASIS)
            }
        }
        return Refinement.Refined(retained)
    }

    private fun rejectedResultInput(reason: QueryExecutionRejectionDocument): Refinement.Rejected<QueryRunRejection> =
        Refinement.Rejected(QueryRunRejection.ExecutionRejected(reason))
}
