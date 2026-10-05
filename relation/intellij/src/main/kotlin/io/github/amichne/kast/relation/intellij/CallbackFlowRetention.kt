package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.RelationBudget

/** Alias routes, detached invocations, and owner bindings share one original result and returned-byte grant. */
internal class CallbackFlowRetention(private val budget: RelationBudget) {
    private var retainedBytes = 0L
    private var retainedResults = 0

    fun admit(bytes: Long): Refinement<Unit, CallbackInvocationFlowCause> =
        when {
            retainedResults >= budget.resources.resultLimit.value ->
                Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED)
            bytes > budget.returnedBytes.value - retainedBytes ->
                Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED)
            else -> {
                retainedBytes += bytes
                retainedResults += 1
                Refinement.Refined(Unit)
            }
        }
}
