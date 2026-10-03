package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement

/** Position in the original retained row set, never a compacted presentation position. */
@JvmInline
value class QueryRetainedRowOrdinal private constructor(val value: Int) {
    companion object {
        fun admit(value: Int, rowCount: Int): Refinement<QueryRetainedRowOrdinal, QueryRetainedResultFailure> =
            if (value in 0 until rowCount) Refinement.Refined(QueryRetainedRowOrdinal(value))
            else Refinement.Rejected(QueryRetainedResultFailure.UNKNOWN_ROW)
    }
}
