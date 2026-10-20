package io.github.amichne.kast.protocol.contract

internal fun ImpactSourceOperationDocument.SeedCallbackReturn.admitsSeedCallbackReturn(
    source: ImpactValueSiteReferenceDocument,
    target: ImpactValueSiteReferenceDocument,
    kind: ImpactTransferKindDocument,
): Boolean {
    val callback = frame.target as? ImpactCallTargetDocument.Callback ?: return false
    return kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
        callback.supplyingFrame.target == ImpactCallTargetDocument.Direct &&
        frame.admits(source.enclosing.basis) &&
        source.matchesSeedCallbackReturn(frame) &&
        target.isResultOf(frame) &&
        source.valuePath == target.valuePath &&
        listOf(callback.supplyingFrame.asFrame()).hasSameFrames(target.callStack.values)
}

private fun ImpactValueSiteReferenceDocument.matchesSeedCallbackReturn(frame: ImpactCallFrameDocument): Boolean =
    role == ImpactValueRoleDocument.Return &&
        callStack.values.isEmpty() &&
        executionOwner is ImpactExecutionOwnerDocument.Anonymous &&
        isIn(frame.callee)
