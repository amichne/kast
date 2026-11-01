package io.github.amichne.kast.protocol.contract

/** Applied correspondence never changes the authority of the selected call or callback owner. */
fun ImpactReviewedValueTransferDocument.admitsEvidence(): Boolean =
    binding.admits(binding.enclosing) &&
        source.isOnBasis(binding.basis) &&
        target.isOnBasis(binding.basis) &&
        source.enclosing == binding.enclosing &&
        target.enclosing == binding.enclosing &&
        source.callStack.hasSameCallStackIdentity(target.callStack) &&
        admitsRelation()

private fun ImpactReviewedValueTransferDocument.admitsRelation(): Boolean =
    when (val relation = binding.relation) {
        is ImpactReviewedValueRelationDocument.CallbackReceiverInput -> admitsScopeReceiver(relation)
        is ImpactReviewedValueRelationDocument.GroupPlacement -> admitsGroup(relation)
        is ImpactReviewedValueRelationDocument.FilteredElements -> admitsFiltered(relation)
        ImpactReviewedValueRelationDocument.NonNullCallbackResult -> admitsNonNullResult()
        is ImpactReviewedValueRelationDocument.EntryInput -> admitsEntryInput(relation)
        is ImpactReviewedValueRelationDocument.PreservedKey -> admitsPreservedKey(relation)
        is ImpactReviewedValueRelationDocument.EntryProjection -> admitsEntryProjection(relation)
        is ImpactReviewedValueRelationDocument.MappedValue -> admitsMapped(relation)
        is ImpactReviewedValueRelationDocument.CallbackCorrespondence -> admitsCallback(relation.semantics)
        is ImpactReviewedValueRelationDocument.ReorderedElements -> admitsSorted(relation)
        is ImpactReviewedValueRelationDocument.TupleConstruction -> admitsTupleConstruction(relation)
        is ImpactReviewedValueRelationDocument.TupleProjection -> admitsTupleProjection(relation)
        is ImpactReviewedValueRelationDocument.AssociationComponent -> admitsAssociation(relation)
    }

private fun ImpactReviewedValueTransferDocument.admitsCallback(
    semantics: ImpactReviewedValueSemanticsDocument
): Boolean {
    val proof = binding.authority as? ImpactReviewedValueBindingDocument ?: return false
    return when (semantics) {
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT -> admitsMapResult(proof)
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT -> admitsLet(proof)
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT ->
            proof.admitsReceiver(source) &&
                admitsCallbackResult(proof) &&
                target.valuePath.values.isEmpty() &&
                source.conservesReviewedReceiverConditions(target, binding)
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER ->
            admitsInput(proof) &&
                source.valuePath.values.firstOrNull() == ImpactValuePathSegmentDocument.Element &&
                source.valuePath.values.drop(1) == target.valuePath.values
        ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER ->
            admitsInput(proof) && source.valuePath.values.isEmpty() && target.valuePath.values.isEmpty()
    }
}

private fun ImpactReviewedValueTransferDocument.admitsMapResult(proof: ImpactReviewedValueBindingDocument): Boolean =
    source.role == ImpactValueRoleDocument.Return &&
        source.executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(proof) &&
        admitsCallbackResult(proof) &&
        listOf(ImpactValuePathSegmentDocument.Element) + source.valuePath.values == target.valuePath.values &&
        source.conditions.hasSameConditions(target.conditions)

private fun ImpactReviewedValueTransferDocument.admitsLet(proof: ImpactReviewedValueBindingDocument): Boolean =
    source.role == ImpactValueRoleDocument.Return &&
        admitsCallbackResult(proof) &&
        source.valuePath == target.valuePath &&
        source.conditions.hasSameConditions(target.conditions) &&
        when (val evidence = proof.invocationEvidence) {
            ImpactReviewedInvocationEvidenceDocument.Unestablished -> false
            is ImpactReviewedInvocationEvidenceDocument.Declared ->
                source.executionOwner == ImpactExecutionOwnerDocument.DeclaredCallback(evidence.binding)
        }

internal fun ImpactReviewedValueTransferDocument.admitsCallbackResult(
    proof: ImpactReviewedValueBindingDocument
): Boolean =
    target.executionOwner == proof.parentExecutionOwner &&
        target.role == ImpactValueRoleDocument.ExpressionResult &&
        target.range == proof.supplierRange

private fun ImpactReviewedValueTransferDocument.admitsInput(proof: ImpactReviewedValueBindingDocument): Boolean {
    if (!proof.matchesReceiver(source) || !source.conditions.hasSameConditions(target.conditions)) return false
    val formal = target.role as? ImpactValueRoleDocument.Formal ?: return false
    val position = formal.position as? ImpactFormalPositionDocument.Parameter ?: return false
    val parameter = position.parameter
    return parameter.index.value == 0 &&
        parameter.callable == proof.enclosing &&
        parameter.range == target.range &&
        parameter.executionOwner == target.executionOwner &&
        target.executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(proof)
}
