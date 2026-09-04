package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation

/** Process deadlines, distinct from semantic work-unit budgets and release performance gates. */
enum class OperationExecutionBudget(operationMillis: Long) {
    SEMANTIC_READ(60_000),
    GRAPH_BUILD(240_000),
    ;

    val operation: ElapsedTimeLimitMillis = limit(operationMillis)
    val invocation: ElapsedTimeLimitMillis
        get() = limit(WORKSPACE_READINESS.value + operation.value)

    companion object {
        val LOCAL_QUALIFICATION: ElapsedTimeLimitMillis = limit(10_000)
        val WORKSPACE_READINESS: ElapsedTimeLimitMillis = limit(17 * 60_000)
        val PROVIDER_QUALIFICATION: ElapsedTimeLimitMillis = limit(2 * LOCAL_QUALIFICATION.value)

        /** Every public operation has one exhaustive process budget authority. */
        fun forOperation(operation: CanonicalOperation): OperationExecutionBudget = when (operation) {
            CanonicalOperation.TOPOLOGY_BUILD -> GRAPH_BUILD
            CanonicalOperation.INDEX_SYNC,
            CanonicalOperation.SYMBOL_DISCOVER,
            CanonicalOperation.SYMBOL_INSPECT,
            CanonicalOperation.SOURCE_READ,
            CanonicalOperation.RELATION_READ,
            CanonicalOperation.TRAVERSAL_RUN,
            CanonicalOperation.DIAGNOSTIC_CHECK,
            CanonicalOperation.CHANGE_PLAN,
            CanonicalOperation.CHANGE_APPLY,
            CanonicalOperation.CHANGE_RECOVER,
                -> SEMANTIC_READ
        }
    }
}

private fun limit(milliseconds: Long): ElapsedTimeLimitMillis = when (
    val admission = ElapsedTimeLimitMillis.parse(milliseconds)
) {
    is Refinement.Refined -> admission.value
    is Refinement.Rejected -> error("Invalid canonical operation deadline")
}
