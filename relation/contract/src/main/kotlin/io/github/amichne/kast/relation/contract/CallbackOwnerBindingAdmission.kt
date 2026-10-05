package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal fun admitCallbackOwnerBindings(
    flow: CallbackInvocationFlow,
    bindings: List<CallbackBodyBinding>,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (bindings.map { it.body }.distinct().size != bindings.size)
        return Refinement.Rejected(CallbackInvocationFlowFailure.DUPLICATE_OWNER_BINDING)
    val owners =
        flow.invocations.map { it.owner } +
            flow.body +
            listOfNotNull((flow.binding as? CallbackBindingEvidence.Bound)?.binding?.invocationOwner)
    if (bindings.any { it.body !in owners })
        return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
    if (
        bindings.any {
            (it.binding as? CallbackBindingEvidence.Bound)?.binding?.invocation?.basis?.let { basis ->
                basis != flow.basis
            } == true
        }
    )
        return Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
    return Refinement.Refined(Unit)
}
