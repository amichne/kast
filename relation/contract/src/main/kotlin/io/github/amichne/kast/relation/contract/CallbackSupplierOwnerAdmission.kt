package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** Supplier exhaustion keeps complete execution routes for each exact anonymous call owner. */
internal fun admitSupplierOwners(
    formal: CallbackParameterIdentity,
    suppliers: List<CallbackParameterSupplier>,
    incoming: List<CallbackParameterForwarding>,
    owners: List<CallbackBodyBinding>,
): Refinement<Unit, CallbackSupplierFailure> {
    val bindings = (suppliers.map { it.binding } + incoming.map { it.target }).distinct()
    when (
        val admitted =
            admitCallbackOwnerBindings(
                formal.callable.lease.identity,
                CallbackInvocationScan.EXHAUSTIVE,
                bindings.map { it.invocationOwner },
                owners,
            )
    ) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return Refinement.Rejected(CallbackSupplierFailure.UNPROVEN_OWNER)
    }
    val composition = CallbackFlowComposition()
    for (binding in bindings) {
        when (val root = composition.owner(binding.invocation.basis, owners, binding.invocationOwner)) {
            is Refinement.Rejected -> return Refinement.Rejected(CallbackSupplierFailure.UNPROVEN_OWNER)
            is Refinement.Refined ->
                if (!binding.invocation.enclosing.matchesCallbackRoot(root.value))
                    return Refinement.Rejected(CallbackSupplierFailure.UNPROVEN_OWNER)
        }
    }
    return Refinement.Refined(Unit)
}
