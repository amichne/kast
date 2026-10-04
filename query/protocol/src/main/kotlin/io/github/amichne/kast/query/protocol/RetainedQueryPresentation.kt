package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryValuePathAccounting

/** One admitted immutable presentation slice retains the exact rows, coverage and offset witness together. */
@ConsistentCopyVisibility
internal data class RetainedQueryPresentation
private constructor(
    val result: QueryResult,
    val coverage: QueryCoverage.Qualified?,
    val producerProgress: QueryContinuationState?,
    val rowIds: List<QueryResultRowReference>,
    val window: QueryRetainedPresentationWindow,
    val originalPathRowIds: List<QueryResultRowReference> = emptyList(),
) {
    companion object {
        fun create(
            restored: QueryResultRestoration.Restored,
            request: QueryRunRequest.ReadResult,
            maximumResults: ResultLimit,
        ): Refinement<RetainedQueryPresentation, QueryExecutionRejectionDocument> {
            val output = request.output
            if (output is QueryOutputDocument.ImpactWitness)
                return witnessPresentation(restored, request, output, maximumResults)
            val rowCount = restored.result.rowCount
            val start = request.cursor.value
            if (start > rowCount) return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE)
            val end = minOf(start.toLong() + minOf(RESULT_PAGE_SIZE, maximumResults.value), rowCount.toLong()).toInt()
            val selected =
                if (restored.result is QueryRetainedResult.ValuePaths) {
                    restored.result.selectRows((start until end).toList()).refinedForQueryOrNull()
                        ?: return contractRejected()
                } else restored.result
            val rows =
                retainedRows(
                    selected,
                    request.output,
                    if (selected is QueryRetainedResult.ValuePaths) 0 else start,
                    if (selected is QueryRetainedResult.ValuePaths) end - start else end,
                ) ?: return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE)
            val window =
                QueryRetainedPresentationWindow.create(
                        request.result,
                        request.cursor,
                        QueryResultCursor.parse(end).refinedForQueryOrNull() ?: return contractRejected(),
                        QueryResultCursor.parse(rowCount).refinedForQueryOrNull() ?: return contractRejected(),
                    )
                    .refinedForQueryOrNull() ?: return contractRejected()
            val result = retainedResult(restored.result, rows)
            val coverage =
                pageCoverage(selected.coverage, start, rowCount, maximumResults).refinedForQueryOrNull()
                    ?: return contractRejected()
            return Refinement.Refined(
                RetainedQueryPresentation(
                    result,
                    coverage as? QueryCoverage.Qualified,
                    selected.producerProgress,
                    restored.rowIds.subList(start, end),
                    window,
                )
            )
        }

        private fun witnessPresentation(
            restored: QueryResultRestoration.Restored,
            request: QueryRunRequest.ReadResult,
            output: QueryOutputDocument.ImpactWitness,
            maximumResults: ResultLimit,
        ): Refinement<RetainedQueryPresentation, QueryExecutionRejectionDocument> {
            val retained =
                restored.result as? QueryRetainedResult.ValuePaths
                    ?: return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE)
            val accounting =
                retained.rows.accounting as? QueryValuePathAccounting.Investigated
                    ?: return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE)
            val section = output.section.witnessSection()
            val count =
                QueryImpactWitnessView.count(accounting.ledger, section).refinedForQueryOrNull()
                    ?: return contractRejected()
            val start = request.cursor.value
            if (start > count.value)
                return Refinement.Rejected(QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE)
            val end =
                minOf(start.toLong() + minOf(RESULT_PAGE_SIZE, maximumResults.value), count.value.toLong()).toInt()
            val view =
                QueryImpactWitnessView.create(accounting.ledger, section, start, end).refinedForQueryOrNull()
                    ?: return contractRejected()
            val window =
                QueryRetainedPresentationWindow.create(
                        request.result,
                        request.cursor,
                        QueryResultCursor.parse(end).refinedForQueryOrNull() ?: return contractRejected(),
                        QueryResultCursor.parse(count.value).refinedForQueryOrNull() ?: return contractRejected(),
                    )
                    .refinedForQueryOrNull() ?: return contractRejected()
            val rowIds =
                originalWitnessRowIds(restored, retained, view).refinedForQueryOrNull() ?: return contractRejected()
            val coverage =
                witnessCoverage(retained, start, count.value, maximumResults).refinedForQueryOrNull()
                    ?: return contractRejected()
            val result = retainedResult(retained, QueryRows.ImpactWitness.of(view))
            return Refinement.Refined(
                RetainedQueryPresentation(
                    result,
                    coverage as? QueryCoverage.Qualified,
                    retained.producerProgress
                        ?: QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
                    rowIds,
                    window,
                    originalSiteRowIds(section, restored),
                )
            )
        }

        private fun originalSiteRowIds(section: QueryImpactWitnessSection, restored: QueryResultRestoration.Restored) =
            if (section == QueryImpactWitnessSection.SITE_ACCOUNTING) restored.rowIds else emptyList()

        private fun originalWitnessRowIds(
            restored: QueryResultRestoration.Restored,
            retained: QueryRetainedResult.ValuePaths,
            view: QueryImpactWitnessView,
        ): Refinement<List<QueryResultRowReference>, QueryExecutionRejectionDocument> {
            if (
                view.section != QueryImpactWitnessSection.FINDINGS &&
                    view.section != QueryImpactWitnessSection.SITE_ACCOUNTING
            )
                return Refinement.Refined(emptyList())
            if (restored.rowIds.size != view.ledger.paths.size || retained.valuePaths != view.ledger.paths)
                return contractRejected()
            if (view.section == QueryImpactWitnessSection.SITE_ACCOUNTING) return Refinement.Refined(emptyList())
            return Refinement.Refined(restored.rowIds.subList(view.firstOrdinal.value, view.nextOrdinal.value))
        }

        private fun pageCoverage(
            original: QueryCoverage,
            start: Int,
            count: Int,
            maximumResults: ResultLimit,
        ): Refinement<QueryCoverage, QueryExecutionRejectionDocument> {
            // The retained window carries presentation progress; paging cannot erase producer completeness.
            val qualified =
                when (original) {
                    is QueryCoverage.Complete -> return Refinement.Refined(original)
                    is QueryCoverage.Qualified -> original
                }
            if (maximumResults.value >= minOf(RESULT_PAGE_SIZE, count - start)) return Refinement.Refined(original)
            val limitations = qualified.limitations.toSet() + QueryLimitation.RESULT_LIMIT_REACHED
            return when (val admitted = QueryCoverage.Qualified.create(qualified.knownMinimum, limitations)) {
                is Refinement.Refined -> admitted
                is Refinement.Rejected -> contractRejected()
            }
        }

        private fun witnessCoverage(
            retained: QueryRetainedResult.ValuePaths,
            start: Int,
            count: Int,
            maximumResults: ResultLimit,
        ): Refinement<QueryCoverage, QueryExecutionRejectionDocument> {
            val known =
                when (val coverage = retained.coverage) {
                    is QueryCoverage.Complete -> coverage.resultCount
                    is QueryCoverage.Qualified -> coverage.knownMinimum
                }
            val limitations =
                ((retained.coverage as? QueryCoverage.Qualified)?.limitations?.toSet() ?: emptySet()) +
                    QueryLimitation.ROW_SELECTION_INCOMPLETE
            return when (val admitted = QueryCoverage.Qualified.create(known, limitations)) {
                is Refinement.Refined -> pageCoverage(admitted.value, start, count, maximumResults)
                is Refinement.Rejected -> contractRejected()
            }
        }

        private fun retainedResult(retained: QueryRetainedResult, rows: QueryRows): QueryResult =
            QueryResult(
                rows,
                retained.failures,
                retained.omissions,
                retained.walkObservations,
                referenceObservations = retained.referenceObservations,
                discoveryObservations = retained.discoveryObservations,
                relationObservations = retained.relationObservations,
            )

        private fun contractRejected(): Refinement.Rejected<QueryExecutionRejectionDocument> =
            Refinement.Rejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
    }
}

private const val RESULT_PAGE_SIZE = 100
