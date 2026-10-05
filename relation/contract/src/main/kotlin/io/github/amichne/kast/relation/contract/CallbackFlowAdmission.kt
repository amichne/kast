package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

/** Binding and invocation factories already preserve their own exact shape and local ownership proofs. */
internal fun admitBoundCallbackFlow(
    basis: SemanticReadIdentity,
    body: RelationCallableBody.Anonymous,
    binding: CallbackArgumentBinding,
    invocations: List<CallbackParameterInvocation>,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (binding.invocation.basis != basis) return Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
    if (body.file != binding.invocation.enclosing.file || !binding.invocation.range.containsValueRange(body.range))
        return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
    val callable = binding.invocation.callable
    if (invocations.any { it.owner.file != callable.file || !callable.range.containsValueRange(it.owner.range) })
        return Refinement.Rejected(CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_CALLABLE)
    if (invocations.any { invocation -> invocation.callableTransfers.any { !it.belongsTo(callable, basis) } })
        return Refinement.Rejected(CallbackInvocationFlowFailure.CALLABLE_TRANSFER_BINDING_MISMATCH)
    return Refinement.Refined(Unit)
}

internal fun admitUnboundCallbackFlow(
    binding: CallbackBindingEvidence.Unavailable,
    invocations: List<CallbackParameterInvocation>,
    obligations: Set<CallbackInvocationFlowCause>,
): Refinement<Unit, CallbackInvocationFlowFailure> =
    if (invocations.isNotEmpty() || binding.cause !in obligations)
        Refinement.Rejected(CallbackInvocationFlowFailure.UNBOUND_INVOCATION)
    else Refinement.Refined(Unit)

/** Mapping inside an anonymous body does not prove how that body's supplying call is activated. */
internal fun callbackExecutionNeedsQualification(
    binding: CallbackArgumentBinding,
    invocations: List<CallbackParameterInvocation>,
): Boolean {
    if (binding.invocationOwner is RelationCallableBody.Anonymous) return true
    return invocations.any {
        val owner = it.owner
        owner !is RelationCallableBody.Named ||
            owner.evidence.compilerIdentity != binding.invocation.callable.compilerIdentity
    }
}

private fun ValueTransfer.belongsTo(callable: RelationEndpoint, basis: SemanticReadIdentity): Boolean =
    source.basis == basis &&
        source.enclosing.valueIdentity == callable.valueIdentity &&
        target.enclosing.valueIdentity == callable.valueIdentity
