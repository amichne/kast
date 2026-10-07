package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal fun admitCallbackOwnerBindings(
    flow: CallbackInvocationFlow,
    bindings: List<CallbackBodyBinding>,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val owners =
        flow.invocations.map { it.owner } +
            flow.body +
            flow.invocations.flatMap { invocation -> invocation.forwardings.map { it.target.invocationOwner } } +
            listOfNotNull(
                when (val binding = flow.binding) {
                    is CallbackBindingEvidence.Bound -> binding.binding.invocationOwner
                    is CallbackBindingEvidence.Direct -> binding.binding.owner
                    is CallbackBindingEvidence.Default,
                    is CallbackBindingEvidence.Unavailable -> null
                }
            )
    return admitCallbackOwnerBindings(flow.basis, flow.scan, owners, bindings)
}

internal fun admitCallbackOwnerBindings(
    basis: io.github.amichne.kast.workspace.contract.SemanticReadIdentity,
    scan: CallbackInvocationScan,
    owners: List<RelationCallableBody>,
    bindings: List<CallbackBodyBinding>,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (bindings.map { it.body }.distinct().size != bindings.size)
        return Refinement.Rejected(CallbackInvocationFlowFailure.DUPLICATE_OWNER_BINDING)
    if (bindings.any { it.body !in owners })
        return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
    if (
        bindings.any {
            when (val binding = it.binding) {
                is CallbackBindingEvidence.Bound -> binding.binding.invocation.basis != basis
                is CallbackBindingEvidence.Default -> binding.binding.parameter.callable.lease.identity != basis
                is CallbackBindingEvidence.Direct -> binding.binding.basis != basis
                is CallbackBindingEvidence.Unavailable -> false
            }
        }
    )
        return Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
    if (
        scan == CallbackInvocationScan.EXHAUSTIVE &&
            bindings.any { binding ->
                binding.obligations.any { it != CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION }
            }
    )
        return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_SCAN_PROOF)
    return Refinement.Refined(Unit)
}
