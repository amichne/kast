package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** Exact ordered evidence selects original row ordinals, including non-prefix presentations. */
internal fun originalInvestigationOrdinals(
    original: QueryRetainedResult.ValuePaths,
    selected: QueryRows.ValuePaths,
): Refinement<List<QueryRetainedRowOrdinal>, QueryRetainedResultFailure> {
    if (
        original.rows.accounting !is QueryValuePathAccounting.Investigated ||
            original.rows.accounting != selected.accounting
    )
        return Refinement.Rejected(QueryRetainedResultFailure.INCONSISTENT_COVERAGE)
    val ordinals = mutableListOf<QueryRetainedRowOrdinal>()
    for (path in selected.values) {
        when (val ordinal = QueryRetainedRowOrdinal.admit(original.valuePaths.indexOf(path), original.rowCount)) {
            is Refinement.Refined -> ordinals += ordinal.value
            is Refinement.Rejected -> return ordinal
        }
    }
    return Refinement.Refined(Collections.unmodifiableList(ordinals))
}
