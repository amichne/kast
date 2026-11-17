package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryByteLimit

internal enum class QueryEntryAdmission {
    ADMITTED,
    PAGE_FULL,
    ITEM_TOO_LARGE,
    OWNER_EXPIRED,
}

/** A paired publisher can retain a source window within the current checkpoint allowance. */
internal val QueryExecutionState.sourceWindowBytes: QueryByteLimit
    get() =
        with(request.budget) {
            if (presentation != null && checkpointBytes.value > returnedBytes.value) checkpointBytes else returnedBytes
        }

/** A retained oversized entry occupies its own page. The paired publisher fits its encoded selector. */
internal fun QueryExecutionState.admitOutput(bytes: Long): QueryEntryAdmission {
    val budget = request.budget
    val allowance = budget.returnedBytes.value
    val charge =
        if (bytes > allowance && budget.storage.permits(bytes)) {
            when (presentation?.admitOversizedEntry()) {
                null -> bytes
                is Refinement.Refined -> allowance
                is Refinement.Rejected -> return QueryEntryAdmission.OWNER_EXPIRED
            }
        } else bytes
    return when {
        consumeOutput(charge) -> QueryEntryAdmission.ADMITTED
        charge > allowance -> QueryEntryAdmission.ITEM_TOO_LARGE
        else -> QueryEntryAdmission.PAGE_FULL
    }
}
