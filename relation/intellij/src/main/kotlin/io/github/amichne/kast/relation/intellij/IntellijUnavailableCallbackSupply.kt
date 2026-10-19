package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtFunction

/** Unsupported formal mappings can still preserve exact syntactic supply facts, without claiming activation. */
internal fun IntellijCallbackFlowContext.unavailableCallbackSupply(
    literal: KtFunction,
    body: RelationCallableBody.Anonymous,
    lexicalOwner: CompilerGroundedSymbolEvidence,
    cause: CallbackInvocationFlowCause,
    summaries: CallbackParameterSummaries,
): CallbackInvocationFlowRead {
    val unbound = unavailable(body, cause)
    if (
        cause !in
            setOf(
                CallbackInvocationFlowCause.STORED_CALLBACK,
                CallbackInvocationFlowCause.RETURNED_CALLBACK,
                CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
            )
    )
        return unbound
    if (endpoint(lexicalOwner) == null) return unbound
    val owner =
        when (val read = IntellijCallbackOwnerBindingReader(this, summaries).readUnavailable(literal, body, cause)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return CallbackInvocationFlowRead.ContractRejected(read.failure)
        }
    when (val retained = summaries.retention.admitOwner(owner)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected ->
            return observed(
                body = body,
                binding = io.github.amichne.kast.relation.contract.CallbackBindingEvidence.Unavailable(cause),
                invocations = emptyList(),
                obligations = setOf(cause, retained.failure),
            )
    }
    return when (unbound) {
        is CallbackInvocationFlowRead.Observed ->
            when (val refined = unbound.flow.withOwnerBindings(listOf(owner))) {
                is Refinement.Refined -> CallbackInvocationFlowRead.Observed(refined.value)
                is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(refined.failure)
            }
        is CallbackInvocationFlowRead.Immutable,
        is CallbackInvocationFlowRead.Unavailable,
        is CallbackInvocationFlowRead.ContractRejected -> unbound
    }
}
