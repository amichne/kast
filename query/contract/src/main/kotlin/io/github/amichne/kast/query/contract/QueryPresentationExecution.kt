package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement

enum class QueryPresentationAdmissionFailure {
    OWNER_EXPIRED
}

/**
 * Scoped permission to leave value-path byte admission to the paired encoded-page publisher. No capability is retained
 * in a plan, checkpoint or store. Construction requires evaluation and its fitting effect together; closing evaluation,
 * rejection, or an exception retires the permission before any later execution can use it.
 */
class QueryPresentationExecution private constructor() {
    private var evaluating = true

    fun admitValuePath(): Refinement<Unit, QueryPresentationAdmissionFailure> =
        if (evaluating) Refinement.Refined(Unit)
        else Refinement.Rejected(QueryPresentationAdmissionFailure.OWNER_EXPIRED)

    companion object {
        suspend fun <Page, Fitted> evaluateAndFit(
            evaluate: suspend (QueryPresentationExecution) -> Page,
            fit: suspend (Page) -> Fitted,
        ): Fitted {
            val owner = QueryPresentationExecution()
            val page =
                try {
                    evaluate(owner)
                } finally {
                    owner.evaluating = false
                }
            return fit(page)
        }
    }
}
