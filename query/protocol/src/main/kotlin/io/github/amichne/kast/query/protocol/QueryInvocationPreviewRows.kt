package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryImpactRowIdentityFailure
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting

/** The issuance keeps the exact stored snapshot, so a selected path keeps its original row identity. */
internal fun invocationPreviewRows(
    items: List<QueryResultItemDocument>,
    rows: QueryRows?,
    retained: QueryResultIssuance.Issued?,
    observation: QueryResultRetentionObservation,
): Refinement<List<QueryResultItemDocument>, QueryRunRejection> {
    val projected =
        if (retained == null) identifyRows(items, null)
        else
            when (val identities = previewIdentities(rows, retained, items.size)) {
                is QueryProjection.Projected -> identifyRows(items, identities.values)
                QueryProjection.Rejected -> QueryProjection.Rejected
            }
    return when (projected) {
        is QueryProjection.Projected -> Refinement.Refined(projected.values)
        QueryProjection.Rejected -> {
            observation.observe(
                QueryResultRetentionEvidence.PreviewRowIdentityRejected(
                    QueryCount.parse(items.size).required(),
                    QueryCount.parse(retained?.rowIds?.size ?: 0).required(),
                )
            )
            Refinement.Rejected(
                QueryRunRejection.ImpactExecutionRejected(
                    QueryImpactExecutionFailure.RowIdentity(QueryImpactRowIdentityFailure.CHANGED_RETAINED_ROWS)
                        .executionDocument()
                )
            )
        }
    }
}

private fun previewIdentities(
    rows: QueryRows?,
    retained: QueryResultIssuance.Issued,
    count: Int,
): QueryProjection.Legacy<QueryResultRowReference> {
    val selected = rows as? QueryRows.ValuePaths
    val stored = (retained.result as? QueryRetainedResult.ValuePaths)?.rows
    if (selected == null || stored == null) return QueryProjection.Projected(retained.rowIds.take(count))
    val accounting = selected.accounting as? QueryValuePathAccounting.Investigated
    val original = stored.accounting as? QueryValuePathAccounting.Investigated
    if (accounting == null && original == null && selected.values == stored.values)
        return QueryProjection.Projected(retained.rowIds.take(count))
    if (accounting == null || original == null || accounting.ledger !== original.ledger) return QueryProjection.Rejected
    val identities = stored.values.zip(retained.rowIds).toMap()
    val result = mutableListOf<QueryResultRowReference>()
    for (path in selected.values.take(count)) {
        result += identities[path] ?: return QueryProjection.Rejected
    }
    return QueryProjection.Projected(result)
}
