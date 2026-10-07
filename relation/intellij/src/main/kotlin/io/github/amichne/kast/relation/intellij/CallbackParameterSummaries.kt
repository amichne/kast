package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Request-owned detached evidence. The exact formal retains its semantic basis; no PSI enters storage. */
internal class CallbackParameterSummaries(
    budget: RelationBudget,
    val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    val retention = CallbackFlowRetention(budget)
    private val summaries = linkedMapOf<CallbackParameterIdentity, CallbackParameterSummary>()

    fun find(formal: CallbackParameterIdentity): CallbackParameterSummary? = summaries[formal]

    fun retain(summary: CallbackParameterSummary): Refinement<Unit, CallbackInvocationFlowCause> {
        if (summary.formal in summaries) return Refinement.Refined(Unit)
        return when (val admitted = retention.admit(summary.retainedBytes)) {
            is Refinement.Refined -> {
                summaries[summary.formal] = summary
                admitted
            }
            is Refinement.Rejected -> admitted
        }
    }
}
