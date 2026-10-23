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
        when (val stack = source.callStack.enter(frame)) {
            is Refinement.Refined -> stack.value == target.callStack
            is Refinement.Rejected -> false
        }
}

private fun ValueSourceOperation.CallbackReturn.matchesOwners(source: ValueSite, target: ValueSite): Boolean {
    val context = source.executionOwner.context as? ValueExecutionContext.Callback
    val owner = invocation.invocation.owner as? RelationCallableBody.Named
    return context != null &&
        owner != null &&
        context.body == invocation.body &&
        invocation in context.invocations &&
        target.enclosing.matchesCallbackRoot(owner.evidence) &&
        target.range == invocation.invocation.occurrence.range &&
        target.executionOwner == frame.callee &&
        frame.callee.context == ValueExecutionContext.Declaration &&
        frame.target == ValueCallTarget.Direct
}

private fun ValueSourceOperation.CallbackReturn.matchesSupplier(source: ValueSite): Boolean {
    val supplied = invocation.supply.invocation
    val call = frame.invocation
    val owner = source.executionOwner.supplierOf(invocation)
    return call.enclosing.valueIdentity == supplied.enclosing.valueIdentity &&
        call.enclosing.valueIdentity == source.enclosing.valueIdentity &&
        call.range == supplied.range &&
        call.callable.valueIdentity == supplied.callable.valueIdentity &&
        owner is Refinement.Refined &&
        call.executionOwner == owner.value
}
