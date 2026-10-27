package io.github.amichne.kast.protocol.contract

/** Wire admission preserves matched identities; only the native boundary establishes execution evidence. */
internal fun ImpactSourceOperationDocument.CallbackReturn.admitsCallbackReturn(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean =
    kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
        source.role == ImpactValueRoleDocument.Return &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        source.valuePath == target.valuePath &&
        source.admitsExecutionOwner() &&
        target.admitsExecutionOwner() &&
        admitsCallbackOwners(source, target) &&
        frame.admits(source.enclosing.basis) &&
        (if (source.callStack.values.lastOrNull()?.hasSameFrameIdentity(frame) == true)
            source.callStack.values.hasSameFrames(target.callStack.values)
        else
            source.callStack.values.size < MAX_VALUE_CALL_FRAMES &&
                source.callStack.values.none { it.hasSameFrameIdentity(frame) } &&
                (source.callStack.values + frame).hasSameFrames(target.callStack.values))

private fun ImpactSourceOperationDocument.CallbackReturn.admitsCallbackOwners(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
): Boolean {
    val context = source.executionOwner as? ImpactExecutionOwnerDocument.Callback
    val supplied = (frame.invocation as? ImpactCallOccurrenceDocument.Declared)?.invocation
    return context != null &&
        supplied != null &&
        invocation.body == context.body &&
        context.selects(invocation) &&
        target.isIn(frame.callee) &&
        target.range == invocation.invocation &&
        invocation.invocationOwner.matches(target.enclosing) &&
        frame.callee.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        frame.target == ImpactCallTargetDocument.Direct &&
        matchesSupplier(source, context, supplied)
}

private fun ImpactSourceOperationDocument.CallbackReturn.matchesSupplier(
    source: ImpactValueSiteReferenceDocument,
    context: ImpactExecutionOwnerDocument.Callback,
    supplied: ImpactInvocationReferenceDocument,
): Boolean =
    frame.enclosing == source.enclosing &&
        frame.enclosing == invocation.enclosing &&
        supplied.range == invocation.supply.range &&
        supplied.callable == invocation.supply.callable &&
        when (val execution = supplied.executionOwner) {
            is ImpactExecutionOwnerDocument.DeclaredCallback -> false
            is ImpactExecutionOwnerDocument.ReviewedCallback -> false
            ImpactExecutionOwnerDocument.Declaration -> invocation.supplyOwner.matches(source.enclosing)
            is ImpactExecutionOwnerDocument.Callback ->
                execution.body == invocation.supplyOwner &&
                    execution.invocations == context.invocations &&
                    execution.invocations.values.count { it.body == execution.body } == 1 &&
                    execution.admitsSelection()
        }

private fun ImpactCapturedBodyDocument.matches(declaration: ImpactDeclarationReferenceDocument): Boolean =
    file == declaration.file && range == declaration.range && compilerIdentity == declaration.compilerIdentity
