package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** A mapped argument enters an admitted anonymous body without inventing a named Function.invoke frame. */
internal fun ValueSourceOperation.CallbackArgumentEntry.admitsCallbackArgument(
    source: ValueSite,
    target: ValueSite,
    kind: ValueTransferKind,
): Boolean =
    kind == ValueTransferKind.SOURCE_CALL_ENTRY &&
        (source.role == ValueRole.ExpressionResult || source.role == ValueRole.LocalRead) &&
        source.range == argumentRange &&
        invocation.invocation.occurrence.range.containsValueRange(argumentRange) &&
        source.executionOwner == supplierFrame.callee &&
        target.role == ValueRole.Formal(ValueFormalPosition.Parameter(parameter)) &&
        parameter.executionOwner == target.executionOwner &&
        preservesSupplierState(source, target) &&
        matchesCallbackOwners(source, target)

private fun ValueSourceOperation.CallbackArgumentEntry.preservesSupplierState(
    source: ValueSite,
    target: ValueSite,
): Boolean =
    source.valuePath == target.valuePath &&
        source.conditions == target.conditions &&
        source.callStack == target.callStack &&
        source.callStack.frames.lastOrNull() == supplierFrame

private fun ValueSourceOperation.CallbackArgumentEntry.matchesCallbackOwners(
    source: ValueSite,
    target: ValueSite,
): Boolean {
    val callback = target.executionOwner.context as? ValueExecutionContext.Callback
    val owner = invocation.invocation.owner as? RelationCallableBody.Named
    val supplier = target.executionOwner.supplierOf(invocation)
    return callback != null &&
        owner != null &&
        callback.body == invocation.body &&
        invocation == callback.selectedInvocation &&
        source.enclosing.matchesCallbackRoot(owner.evidence) &&
        target.enclosing.valueIdentity == invocation.supply.invocation.enclosing.valueIdentity &&
        supplierFrame.callee.context == ValueExecutionContext.Declaration &&
        supplierFrame.target == ValueCallTarget.Direct &&
        supplierFrame.invocation == invocation.supply.invocation &&
        supplier is Refinement.Refined &&
        supplier.value == supplierFrame.invocation.executionOwner
}
