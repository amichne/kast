package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** A callback result enters only its proved formal call, with the exact supplying wrapper frame. */
internal fun ValueSourceOperation.CallbackReturn.admitsCallbackReturn(
    source: ValueSite,
    target: ValueSite,
    kind: ValueTransferKind,
): Boolean {
    return kind == ValueTransferKind.SOURCE_CALL_RETURN &&
        source.role == ValueRole.Return &&
        target.role == ValueRole.ExpressionResult &&
        source.valuePath == target.valuePath &&
        matchesOwners(source, target) &&
        matchesSupplier(source) &&
        (if (source.callStack.frames.lastOrNull() == frame) source.callStack == target.callStack
        else
            when (val stack = source.callStack.enter(frame)) {
                is Refinement.Refined -> stack.value == target.callStack
                is Refinement.Rejected -> false
            })
}

private fun ValueSourceOperation.CallbackReturn.matchesOwners(source: ValueSite, target: ValueSite): Boolean {
    val context = source.executionOwner.context as? ValueExecutionContext.Callback
    val owner = invocation.invocation.owner as? RelationCallableBody.Named
    return context != null &&
        owner != null &&
        context.body == invocation.body &&
        invocation == context.selectedInvocation &&
        target.enclosing.matchesCallbackRoot(owner.evidence) &&
        target.range == invocation.invocation.occurrence.range &&
        target.executionOwner == frame.callee &&
        frame.callee.context == ValueExecutionContext.Declaration
}

private fun ValueSourceOperation.CallbackReturn.matchesSupplier(source: ValueSite): Boolean {
    val owner = source.executionOwner.supplierOf(invocation)
    if (owner !is Refinement.Refined) return false
    when (val route = ValueCallbackCallRoute.fromActiveStack(invocation, source.callStack)) {
        is Refinement.Refined ->
            return frame == route.value.invocationFrame &&
                route.value.supplierFrame.invocation.executionOwner == owner.value
        is Refinement.Rejected -> if (invocation.invocation.forwardings.isNotEmpty()) return false
    }
    val supplied = invocation.supply.invocation
    val call = frame.invocation
    return frame.target == invocation.supply.frame.target &&
        frame.callee == invocation.supply.frame.callee &&
        call.enclosing.valueIdentity == supplied.enclosing.valueIdentity &&
        call.enclosing.valueIdentity == source.enclosing.valueIdentity &&
        call.range == supplied.range &&
        call.callable.valueIdentity == supplied.callable.valueIdentity &&
        call.executionOwner == owner.value
}
