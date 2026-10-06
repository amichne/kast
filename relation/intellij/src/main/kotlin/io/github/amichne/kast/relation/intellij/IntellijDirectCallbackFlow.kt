package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtFunction

/** A direct invocation retains its enclosing anonymous owner's supply without inventing a receiving formal. */
internal fun readDirectCallbackFlow(
    context: IntellijCallbackFlowContext,
    prepared: CallbackBindingPreparation.Direct,
    body: RelationCallableBody.Anonymous,
    lexicalOwner: CompilerGroundedSymbolEvidence,
): CallbackInvocationFlowRead {
    val owner = prepared.binding.owner
    val obligations =
        if (owner is RelationCallableBody.Anonymous) setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        else emptySet()
    fun observed(additional: Set<CallbackInvocationFlowCause> = emptySet()) =
        context.observed(
            body,
            CallbackBindingEvidence.Direct(prepared.binding),
            emptyList(),
            obligations + additional,
            CallbackInvocationScan.NOT_APPLICABLE,
        )
    if (owner !is RelationCallableBody.Anonymous) return observed()
    val literal =
        (prepared.call.nearestDeclaration() as? ContainingDeclaration.Deferred)?.boundary as? KtFunction
            ?: return observed(setOf(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE))
    val enclosing =
        context.endpoint(lexicalOwner)
            ?: return observed(setOf(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE))
    val binding =
        when (val read = IntellijCallbackOwnerBindingReader(context).read(literal, owner, enclosing)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return CallbackInvocationFlowRead.ContractRejected(read.failure)
        }
    when (val permitted = CallbackFlowRetention(context.scope.request.budget).admit(binding.retainedBytes)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return observed(setOf(permitted.failure))
    }
    return when (val read = observed(binding.obligations)) {
        is CallbackInvocationFlowRead.Observed ->
            when (val refined = read.flow.withOwnerBindings(listOf(binding))) {
                is Refinement.Refined -> CallbackInvocationFlowRead.Observed(refined.value)
                is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(refined.failure)
            }
        is CallbackInvocationFlowRead.Unavailable,
        is CallbackInvocationFlowRead.ContractRejected -> read
    }
}
