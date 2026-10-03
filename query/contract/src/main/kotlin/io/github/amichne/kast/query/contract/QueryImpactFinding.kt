package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement

/** One original path, retained by reference with its admitted position; no grouping or new semantic evidence. */
class QueryImpactFinding private constructor(val pathOrdinal: QueryRetainedRowOrdinal, val path: QueryImpactPath) {
    companion object {
        internal fun fromOriginalPath(
            ledger: QueryImpactLedger,
            ordinal: Int,
        ): Refinement<QueryImpactFinding, QueryImpactWitnessFailure> =
            when (val position = QueryRetainedRowOrdinal.admit(ordinal, ledger.paths.size)) {
                is Refinement.Refined -> Refinement.Refined(QueryImpactFinding(position.value, ledger.paths[ordinal]))
                is Refinement.Rejected -> Refinement.Rejected(QueryImpactWitnessFailure.INVALID_RANGE)
            }
    }
}
