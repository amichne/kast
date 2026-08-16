package io.github.amichne.kast.traversal.contract

/** Public `traversal.run` boundary. */
fun interface TraversalOperations {
    /**
     * Proof transition: `TraversalPlan -> TraversalResult`.
     *
     * A complete result establishes deterministic exhaustion of one closed relation meaning under
     * the exact selector scope. A qualified result retains bounded resumable state; expected
     * failures are the closed [TraversalRejection] family. Raw selectors, limits, and continuation
     * decoding may enter only before [TraversalPlan] construction.
     */
    suspend fun run(plan: TraversalPlan): TraversalResult
}
