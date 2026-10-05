package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtFunctionLiteral

/** Unsupported formal mappings can still preserve exact syntactic supply facts, without claiming activation. */
internal fun unavailableCallbackSupply(
    context: IntellijCallbackFlowContext,
    literal: KtFunctionLiteral,
    body: RelationCallableBody.Anonymous,
    lexicalOwner: CompilerGroundedSymbolEvidence,
    cause: CallbackInvocationFlowCause,
): CallbackInvocationFlowRead {
    val unbound = context.unavailable(body, cause)
    if (
        cause !in
            setOf(
                CallbackInvocationFlowCause.STORED_CALLBACK,
                CallbackInvocationFlowCause.RETURNED_CALLBACK,
                CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
            )
    )
        return unbound
    val enclosing = context.endpoint(lexicalOwner) ?: return unbound
    val owner =
        when (val read = IntellijCallbackOwnerBindingReader(context).read(literal, body, enclosing)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return CallbackInvocationFlowRead.ContractRejected(read.failure)
        }
    when (val retained = CallbackFlowRetention(context.scope.request.budget).admit(owner.retainedBytes, 0)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected ->
            return context.observed(
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
        is CallbackInvocationFlowRead.Unavailable,
        is CallbackInvocationFlowRead.ContractRejected -> unbound
    }
}
