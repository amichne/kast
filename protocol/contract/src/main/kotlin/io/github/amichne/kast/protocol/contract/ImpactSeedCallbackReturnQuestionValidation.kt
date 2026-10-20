package io.github.amichne.kast.protocol.contract

/** Structural output admission preserves represented facts without native scope or compiler authority. */
internal fun ImpactValueSiteReferenceDocument.admitsSeedCallbackReturnQuestion(
    flow: QueryCallbackFlowWireDocument.Observed
): Boolean {
    if (role != ImpactValueRoleDocument.Return || callStack.values.isNotEmpty()) return false
    val owner = executionOwner as? ImpactExecutionOwnerDocument.Anonymous ?: return false
    val bound = flow.binding as? QueryCallbackBindingWireDocument.Bound ?: return false
    return enclosing.basis == flow.basis &&
        admitsExecutionOwner() &&
        flow.body.matchesOwner(enclosing, owner) &&
        bound.invocationOwner.matchesOwner(enclosing, ImpactExecutionOwnerDocument.Declaration) &&
        bound.invocation.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        bound.invocationOccurrence.file == enclosing.file.value &&
        bound.invocation.range.containsSeedCallbackBody(owner.body.range)
}

private fun ImpactSourceRangeDocument.containsSeedCallbackBody(body: ImpactSourceRangeDocument): Boolean =
    start.value <= body.start.value && body.end.value <= end.value
