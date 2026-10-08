package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection

/** Preserve rejection authority and fit only its preview using the actual complete envelope measurement. */
internal fun fitQueryCompletionRejection(
    page: QueryPublishedPage,
    presentation: (QueryPublishedPage) -> QueryInlinePresentation,
    invalid: () -> QueryPublishedPage,
): QueryPublishedPage {
    val rejected = page as? OperationOutcome.Rejected ?: return page
    val reason = rejected.reason as? QueryRunRejection.CompletionUnproven ?: return page
    val retained = reason.evidence as? QueryCompletionEvidenceDocument.Retained ?: return page
    when (presentation(page)) {
        QueryInlinePresentation.FITS -> return page
        QueryInlinePresentation.INVALID -> return invalid()
        QueryInlinePresentation.RETENTION_REQUIRED -> Unit
    }
    val empty = completionPrefix(reason, retained, 0)
    when (presentation(empty)) {
        QueryInlinePresentation.FITS -> Unit
        // The outer byte guard owns typed rejection when mandatory authority does not fit.
        QueryInlinePresentation.RETENTION_REQUIRED -> return page
        QueryInlinePresentation.INVALID -> return invalid()
    }
    var lower = 1
    var upper = retained.preview.values.size - 1
    var best: QueryPublishedPage = empty
    while (lower <= upper) {
        val count = lower + (upper - lower) / 2
        val candidate = completionPrefix(reason, retained, count)
        when (presentation(candidate)) {
            QueryInlinePresentation.FITS -> {
                best = candidate
                lower = count + 1
            }
            QueryInlinePresentation.RETENTION_REQUIRED -> upper = count - 1
            QueryInlinePresentation.INVALID -> return invalid()
        }
    }
    return best
}

private fun completionPrefix(
    reason: QueryRunRejection.CompletionUnproven,
    retained: QueryCompletionEvidenceDocument.Retained,
    count: Int,
): QueryPublishedPage =
    OperationOutcome.Rejected(
        reason.copy(
            evidence =
                retained.copy(preview = BoundedProtocolList.create(retained.preview.values.take(count)).required())
        )
    )
