package io.github.amichne.kast.protocol.contract

/** Wire admission keeps applied correspondence separate from callback-owner authority. */
fun ImpactReviewedValueTransferDocument.admitsEvidence(): Boolean =
    binding.admits(binding.enclosing) &&
        source.isOnBasis(binding.basis) &&
        target.isOnBasis(binding.basis) &&
        source.enclosing == binding.enclosing &&
        target.enclosing == binding.enclosing &&
        source.callStack.hasSameCallStackIdentity(target.callStack) &&
        admitsRelation()

private fun ImpactReviewedValueTransferDocument.admitsRelation(): Boolean =
    when (binding.semantics) {
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT ->
            admitsMapReturn() && source.conditions.hasSameConditions(target.conditions)
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT ->
            admitsScopeReturn() &&
                source.valuePath == target.valuePath &&
                source.conditions.hasSameConditions(target.conditions)
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT ->
            binding.matchesReceiver(source) &&
                source.valuePath.values.isEmpty() &&
                target.valuePath.values.isEmpty() &&
                admitsResult() &&
                source.conservesReviewedReceiverConditions(target, binding)
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER -> admitsElementInput()
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER ->
            admitsInput() && source.valuePath.values.isEmpty() && target.valuePath.values.isEmpty()
    }

private fun ImpactReviewedValueTransferDocument.admitsMapReturn(): Boolean =
    source.role == ImpactValueRoleDocument.Return &&
        admitsResult() &&
        when (val owner = source.executionOwner) {
            is ImpactExecutionOwnerDocument.ReviewedCallback -> binding.matchesOwner(owner.binding)
            else -> false
        } &&
        listOf(ImpactValuePathSegmentDocument.Element) + source.valuePath.values == target.valuePath.values

private fun ImpactReviewedValueTransferDocument.admitsScopeReturn(): Boolean =
    source.role == ImpactValueRoleDocument.Return &&
        admitsResult() &&
        when (val evidence = binding.invocationEvidence) {
            ImpactReviewedInvocationEvidenceDocument.Unestablished -> false
            is ImpactReviewedInvocationEvidenceDocument.Declared ->
                source.executionOwner == ImpactExecutionOwnerDocument.DeclaredCallback(evidence.binding)
        }

private fun ImpactReviewedValueTransferDocument.admitsResult(): Boolean =
    target.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        target.range == binding.supplierRange

private fun ImpactReviewedValueTransferDocument.admitsInput(): Boolean {
    if (!binding.matchesReceiver(source) || !source.conditions.hasSameConditions(target.conditions)) return false
    val formal = target.role as? ImpactValueRoleDocument.Formal ?: return false
    val position = formal.position as? ImpactFormalPositionDocument.Parameter ?: return false
    val parameter = position.parameter
    val owner = parameter.executionOwner as? ImpactExecutionOwnerDocument.ReviewedCallback ?: return false
    return parameter.index.value == 0 &&
        parameter.callable == binding.enclosing &&
        parameter.range == target.range &&
        parameter.executionOwner == target.executionOwner &&
        binding.matchesOwner(owner.binding)
}

private fun ImpactReviewedValueTransferDocument.admitsElementInput(): Boolean =
    admitsInput() &&
        source.valuePath.values.firstOrNull() == ImpactValuePathSegmentDocument.Element &&
        source.valuePath.values.drop(1) == target.valuePath.values
