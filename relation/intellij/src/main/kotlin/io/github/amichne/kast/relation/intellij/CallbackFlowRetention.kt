package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.RelationBudget

/** Temporary alias routes and detached invocations share the same original returned-byte grant. */
internal class CallbackFlowRetention(private val budget: RelationBudget) {
    private var retainedBytes = 0L

    fun admit(bytes: Long, count: Int): Refinement<Unit, CallbackInvocationFlowCause> =
        when {
            count >= budget.resources.resultLimit.value ->
                Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED)
            bytes > budget.returnedBytes.value - retainedBytes ->
                Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED)
            else -> {
                retainedBytes += bytes
                Refinement.Refined(Unit)
            }
        }
}
