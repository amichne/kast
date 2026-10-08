package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting

internal fun invocationAccounting(
    rows: QueryRows?,
    output: QueryOutputDocument.ImpactWitness?,
    previewSize: Int,
): Refinement<ImpactAccountingDocument?, QueryRunRejection> {
    if (rows !is QueryRows.ValuePaths) return Refinement.Refined(null)
    val selected =
        if (output == null) rows.selectRows((0 until previewSize).toList()).required()
        else {
            val ledger = (rows.accounting as? QueryValuePathAccounting.Investigated)?.ledger ?: return contractFailure()
            QueryRows.ImpactWitness.of(
                QueryImpactWitnessView.create(ledger, output.section.witnessSection(), 0, previewSize).required()
            )
        }
    return Refinement.Refined(selected.impactAccountingDocument().required())
}

internal fun invocationWitnessPreview(
    rows: QueryRows?,
    output: QueryOutputDocument.ImpactWitness,
    retained: QueryResultIssuance.Issued?,
    end: Int,
): Refinement<List<QueryResultItemDocument>, QueryRunRejection> {
    val issued = retained ?: return contractFailure()
    val paths = rows as? QueryRows.ValuePaths ?: return contractFailure()
    val ledger = (paths.accounting as? QueryValuePathAccounting.Investigated)?.ledger ?: return contractFailure()
    val view = QueryImpactWitnessView.create(ledger, output.section.witnessSection(), 0, end).required()
    return when (
        val projected =
            QueryRows.ImpactWitness.of(view)
                .projectWitnessItems(
                    if (view.section == QueryImpactWitnessSection.FINDINGS) issued.rowIds.take(end) else null,
                    if (view.section == QueryImpactWitnessSection.SITE_ACCOUNTING) issued.rowIds else null,
                )
    ) {
        is QueryProjection.Projected -> Refinement.Refined(projected.values)
        is QueryProjection.ImpactRejected -> Refinement.Rejected(projected.cause.presentationRejection())
        QueryProjection.Rejected -> contractFailure()
    }
}

private fun contractFailure() =
    Refinement.Rejected(
        QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
    )
