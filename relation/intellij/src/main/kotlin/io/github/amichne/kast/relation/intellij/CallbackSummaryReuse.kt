package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.RelationCallableBody

internal sealed interface CallbackSummaryRestore {
    data object Missing : CallbackSummaryRestore

    data class Reused(val flow: CallbackInvocationFlow) : CallbackSummaryRestore
}

internal sealed interface CallbackSummaryCandidate {
    data object None : CallbackSummaryCandidate

    data class Admitted(val summary: CallbackParameterSummary) : CallbackSummaryCandidate
}

/** Detached formal facts precede supplier activation. Storage is optional after final flow admission. */
internal class CallbackSummaryReuse(private val summaries: CallbackParameterSummaries) {
    fun restore(
        formal: CallbackParameterIdentity,
        body: RelationCallableBody.Anonymous,
        binding: CallbackBindingEvidence,
    ): Refinement<CallbackSummaryRestore, CallbackInvocationFlowFailure> {
        val cached = summaries.find(formal) ?: return Refinement.Refined(CallbackSummaryRestore.Missing)
        return when (val instance = cached.instantiate(body, binding)) {
            is Refinement.Refined -> Refinement.Refined(CallbackSummaryRestore.Reused(instance.value))
            is Refinement.Rejected -> instance
        }
    }

    fun capture(
        formal: CallbackParameterIdentity,
        invocations: List<CallbackParameterInvocation>,
        obligations: Set<CallbackInvocationFlowCause>,
        owners: List<CallbackBodyBinding>,
        scan: CallbackInvocationScan,
    ): Refinement<CallbackSummaryCandidate, CallbackInvocationFlowFailure> {
        if (scan != CallbackInvocationScan.EXHAUSTIVE) return Refinement.Refined(CallbackSummaryCandidate.None)
        return when (
            val captured = CallbackParameterSummary.fromCompiler(formal, invocations, obligations, owners, scan)
        ) {
            is Refinement.Refined -> Refinement.Refined(CallbackSummaryCandidate.Admitted(captured.value))
            is Refinement.Rejected -> captured
        }
    }

    fun publish(
        result: CallbackInvocationFlowRead,
        owners: List<CallbackBodyBinding>,
        candidate: CallbackSummaryCandidate,
    ): CallbackInvocationFlowRead =
        when (result) {
            is CallbackInvocationFlowRead.Observed ->
                when (val admitted = result.flow.withOwnerBindings(owners)) {
                    is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(admitted.failure)
                    is Refinement.Refined -> {
                        retainOptional(candidate)
                        CallbackInvocationFlowRead.Observed(admitted.value)
                    }
                }
            is CallbackInvocationFlowRead.Unavailable,
            is CallbackInvocationFlowRead.ContractRejected -> result
        }

    private fun retainOptional(candidate: CallbackSummaryCandidate) {
        when (candidate) {
            CallbackSummaryCandidate.None -> Unit
            is CallbackSummaryCandidate.Admitted ->
                when (summaries.retain(candidate.summary)) {
                    is Refinement.Refined,
                    is Refinement.Rejected -> Unit
                }
        }
    }
}
