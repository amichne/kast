package io.github.amichne.kast.protocol.contract

/** Structural output admission cannot establish scope or compiler authority absent from declaration references. */
internal fun ImpactValueSiteReferenceDocument.admitsSeedCallbackQuestion(
    flow: QueryCallbackFlowWireDocument.Observed
): Boolean {
    if (callStack.values.isNotEmpty() || executionOwner != ImpactExecutionOwnerDocument.Declaration) return false
    val actual = role as? ImpactValueRoleDocument.FunctionArgument ?: return false
    val binding = flow.binding as? QueryCallbackBindingWireDocument.Bound ?: return false
    return enclosing.basis == flow.basis &&
        admitsExecutionOwner() &&
        actual.invocation.executionOwner == executionOwner &&
        binding.invocation.callable == enclosing &&
        binding.parameter.file == enclosing.file.value &&
        binding.parameter.range.startInclusive >= enclosing.range.start.value &&
        binding.parameter.range.endExclusive <= enclosing.range.end.value
}
