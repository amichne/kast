package io.github.amichne.kast.protocol.contract

internal fun ImpactReviewedValueTransferDocument.admitsEntryInput(
    relation: ImpactReviewedValueRelationDocument.EntryInput
): Boolean {
    val proof = binding.authority as? ImpactReviewedValueBindingDocument ?: return false
    return source.matchesEntrySelection(relation.entry.selection, relation.slot) &&
        relation.entry.matchesReceiver(source) &&
        target.admitsEntryFormal(proof) &&
        listOf(ImpactValuePathSegmentDocument.MapEntryComponent(relation.entry, relation.slot)) +
            source.valuePath.values.drop(1) == target.valuePath.values &&
        source.conditions.hasSameConditions(target.conditions) &&
        source.admitsValueState() &&
        target.admitsValueState()
}

private fun ImpactValueSiteReferenceDocument.admitsEntryFormal(proof: ImpactReviewedValueBindingDocument): Boolean {
    val formal = role as? ImpactValueRoleDocument.Formal ?: return false
    val parameter = (formal.position as? ImpactFormalPositionDocument.Parameter)?.parameter ?: return false
    return parameter.index.value == 0 &&
        parameter.callable == proof.enclosing &&
        parameter.range == range &&
        parameter.executionOwner == executionOwner &&
        executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(proof)
}

internal fun ImpactReviewedValueTransferDocument.admitsPreservedKey(
    relation: ImpactReviewedValueRelationDocument.PreservedKey
): Boolean {
    val proof = binding.authority as? ImpactReviewedValueBindingDocument ?: return false
    return source.matchesEntrySelection(relation.entry.selection, ImpactMapEntrySlotDocument.KEY) &&
        relation.entry.matchesReceiver(source) &&
        admitsCallbackResult(proof) &&
        listOf(ImpactValuePathSegmentDocument.MapEntryComponent(relation.entry, ImpactMapEntrySlotDocument.KEY)) +
            source.valuePath.values.drop(1) == target.valuePath.values &&
        source.conservesPreservedKeyCondition(target, relation.entry) &&
        source.admitsValueState() &&
        target.admitsValueState()
}

internal fun ImpactReviewedValueTransferDocument.admitsEntryProjection(
    relation: ImpactReviewedValueRelationDocument.EntryProjection
): Boolean {
    val proof = binding.authority as? ImpactReviewedCallValueBindingDocument ?: return false
    return source.matchesEntryRead(proof, relation.entry, relation.slot) &&
        target.isOrdinaryResult(proof) &&
        source.valuePath.values.drop(1) == target.valuePath.values &&
        source.conservesEntryReadConditions(target, proof, relation.entry, relation.slot) &&
        source.admitsValueState() &&
        target.admitsValueState()
}

internal fun ImpactValueSiteReferenceDocument.matchesEntryRead(
    proof: ImpactReviewedCallValueBindingDocument,
    entry: ImpactReviewedMapEntryOriginDocument,
    slot: ImpactMapEntrySlotDocument,
): Boolean {
    val receiver = role as? ImpactValueRoleDocument.Receiver ?: return false
    val kind =
        when (proof.rule) {
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT1_V1,
            ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_MAP_ENTRY_COMPONENT2_V1 -> ImpactReceiverKindDocument.EXTENSION
            else -> ImpactReceiverKindDocument.DISPATCH
        }
    return enclosing == proof.enclosing &&
        executionOwner == proof.invocation.executionOwner &&
        proof.invocation.range.containsExecutionRange(range) &&
        receiver.invocation == proof.invocation &&
        receiver.kind == kind &&
        proof.rule.admitsEntrySlot(slot) &&
        valuePath.values.firstOrNull() == ImpactValuePathSegmentDocument.MapEntryComponent(entry, slot)
}
