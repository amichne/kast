package io.github.amichne.kast.protocol.contract

internal fun ImpactSourceOperationDocument.CallbackArgumentEntry.admitsCallbackArgument(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
        (source.role == ImpactValueRoleDocument.ExpressionResult || source.role == ImpactValueRoleDocument.LocalRead) &&
        source.admitsExecutionOwner() &&
        target.admitsExecutionOwner() &&
        admitsActualArgument(source) &&
        target.role.hasSameRoleIdentity(
            ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(parameter))
        ) &&
        parameter.executionOwner.hasSameExecutionIdentity(target.executionOwner) &&
        preservesSupplierState(source, target) &&
        matchesCallbackOwners(source, target)

private fun ImpactSourceOperationDocument.CallbackArgumentEntry.admitsActualArgument(
    source: ImpactValueSiteReferenceDocument
): Boolean =
    source.range == argumentRange &&
        invocation.invocation.containsExecutionRange(argumentRange) &&
        supplierFrame.admits(source.enclosing.basis)

private fun ImpactSourceOperationDocument.CallbackArgumentEntry.preservesSupplierState(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
): Boolean =
    source.valuePath == target.valuePath &&
        source.conditions.hasSameConditions(target.conditions) &&
        source.callStack.hasSameCallStackIdentity(target.callStack)

private fun ImpactSourceOperationDocument.CallbackArgumentEntry.matchesCallbackOwners(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
): Boolean {
    if (!matchesActiveCallRoute(source)) return false
    val callback = target.executionOwner as? ImpactExecutionOwnerDocument.Callback
    val supplied = (supplierFrame.invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation
    return callback != null &&
        supplied != null &&
        invocation.body == callback.body &&
        callback.selects(invocation) &&
        invocation.invocationOwner.matchesArgumentOwner(source.enclosing) &&
        supplierFrame.callee.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        supplierFrame.enclosing == target.enclosing &&
        supplierFrame.enclosing == invocation.enclosing &&
        supplied.hasSameInvocationIdentity(invocation.supply) &&
        matchesSupplierExecution(supplied.executionOwner, target, callback)
}

private fun ImpactSourceOperationDocument.CallbackArgumentEntry.matchesActiveCallRoute(
    source: ImpactValueSiteReferenceDocument
): Boolean {
    val route = invocation.activeCallRoute(source.callStack) ?: return false
    return route.supplierFrame.hasSameFrameIdentity(supplierFrame) && source.isIn(route.invocationFrame.callee)
}

private fun ImpactSourceOperationDocument.CallbackArgumentEntry.matchesSupplierExecution(
    execution: ImpactExecutionOwnerDocument,
    target: ImpactValueSiteReferenceDocument,
    callback: ImpactExecutionOwnerDocument.Callback,
): Boolean =
    when (execution) {
        ImpactExecutionOwnerDocument.Declaration -> invocation.supplyOwner.matchesArgumentOwner(target.enclosing)
        is ImpactExecutionOwnerDocument.Callback ->
            execution.body == invocation.supplyOwner &&
                execution.matchesSupplierProof(invocation.supply.executionOwner, callback) &&
                execution.admitsSelection()
        is ImpactExecutionOwnerDocument.DeclaredCallback -> false
        is ImpactExecutionOwnerDocument.ReviewedCallback -> false
    }

private fun ImpactCapturedBodyDocument.matchesArgumentOwner(declaration: ImpactDeclarationReferenceDocument): Boolean =
    file == declaration.file && range == declaration.range && compilerIdentity == declaration.compilerIdentity
