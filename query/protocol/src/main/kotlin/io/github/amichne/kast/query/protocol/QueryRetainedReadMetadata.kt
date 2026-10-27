package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
import io.github.amichne.kast.protocol.contract.QueryEvidenceSummaryState
import io.github.amichne.kast.protocol.contract.QueryOriginalEvidenceSummaryDocument

/** Presentation metadata preserves the original summary and the selected detail address. */
internal fun withRetainedReadMetadata(
    outcome: QueryPublishedPage,
    detail: QueryEntryDetailSelectorDocument?,
    summary: QueryOriginalEvidenceSummaryDocument,
): QueryPublishedPage {
    val captured = QueryEvidenceSummaryState.Captured(summary)
    return when (outcome) {
        is OperationOutcome.Complete ->
            outcome.copy(
                evidence =
                    outcome.evidence.copy(
                        payload =
                            outcome.evidence.payload.copy(
                                detailSelection = detail,
                                originalEvidenceSummary = captured,
                            )
                    )
            )
        is OperationOutcome.Qualified ->
            outcome.copy(
                evidence =
                    outcome.evidence.copy(
                        payload =
                            outcome.evidence.payload.copy(
                                detailSelection = detail,
                                originalEvidenceSummary = captured,
                            )
                    )
            )
        is OperationOutcome.Rejected -> outcome
    }
}
