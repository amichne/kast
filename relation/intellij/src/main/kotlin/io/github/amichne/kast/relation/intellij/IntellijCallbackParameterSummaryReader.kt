package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackParameterSummary

internal sealed interface CallbackParameterSummaryRead {
    data class Available(val summary: CallbackParameterSummary) : CallbackParameterSummaryRead

    data class Unavailable(
        val cause: CallbackInvocationFlowCause,
        val additional: Set<CallbackInvocationFlowCause> = emptySet(),
    ) : CallbackParameterSummaryRead

    data class ContractRejected(val cause: CallbackInvocationFlowFailure) : CallbackParameterSummaryRead
}

/** One shared formal-body reader for named, anonymous and immutable transported suppliers. */
internal class IntellijCallbackParameterSummaryReader(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(prepared: PreparedCallbackFlow): CallbackParameterSummaryRead {
        val formal =
            when (val read = prepared.formal()) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return CallbackParameterSummaryRead.Unavailable(read.failure)
            }
        summaries.find(formal)?.let {
            return CallbackParameterSummaryRead.Available(it)
        }
        val snapshot =
            when (val read = IntellijCallbackFlowScan(context, prepared, summaries).read(formal)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return CallbackParameterSummaryRead.ContractRejected(read.failure)
            }
        snapshot.obligations.firstOrNull()?.let {
            return CallbackParameterSummaryRead.Unavailable(it, snapshot.obligations)
        }
        return when (
            val admitted =
                CallbackParameterSummary.fromCompiler(
                    formal,
                    snapshot.invocations,
                    snapshot.obligations,
                    snapshot.owners,
                    snapshot.scan,
                    snapshot.forwarding,
                )
        ) {
            is Refinement.Refined ->
                when (val retained = summaries.retain(admitted.value)) {
                    is Refinement.Refined -> CallbackParameterSummaryRead.Available(admitted.value)
                    is Refinement.Rejected -> CallbackParameterSummaryRead.Unavailable(retained.failure)
                }
            is Refinement.Rejected ->
                CallbackParameterSummaryRead.Unavailable(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN)
        }
    }
}
