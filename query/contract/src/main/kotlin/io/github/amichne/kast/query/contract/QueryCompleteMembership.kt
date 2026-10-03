package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

enum class QueryMembershipFailure {
    INCOMPLETE
}

/** A proof bound to the exact retained input whose entire membership is known. */
class QueryCompleteMembership private constructor(val source: QueryRetainedResult.Symbols) {
    val lease: SemanticReadAuthority
        get() = source.lease

    val symbols: List<QuerySymbol>
        get() = source.symbols

    companion object {
        fun from(result: QueryRetainedResult.Symbols): Refinement<QueryCompleteMembership, QueryMembershipFailure> =
            if (result.coverage !is QueryCoverage.Complete) {
                Refinement.Rejected(QueryMembershipFailure.INCOMPLETE)
            } else if (
                result.failures.isNotEmpty() || result.omissions.isNotEmpty() || result.producerProgress != null
            ) {
                Refinement.Rejected(QueryMembershipFailure.INCOMPLETE)
            } else {
                Refinement.Refined(QueryCompleteMembership(result))
            }
    }
}
