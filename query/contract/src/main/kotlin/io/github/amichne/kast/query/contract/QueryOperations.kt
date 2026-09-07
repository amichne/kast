package io.github.amichne.kast.query.contract

/** Public in-process evaluator for one already admitted typed query plan. */
fun interface QueryOperations {
    /**
     * Proof transition: `QueryExecutionRequest -> QueryExecutionResult`.
     *
     * Successful results retain item exactness independently from aggregate coverage. Expected
     * refinement and expansion failures remain finite data rather than silently disappearing.
     */
    suspend fun run(request: QueryExecutionRequest): QueryExecutionResult
}
