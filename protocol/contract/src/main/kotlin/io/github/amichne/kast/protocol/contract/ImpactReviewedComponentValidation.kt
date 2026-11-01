package io.github.amichne.kast.protocol.contract

internal fun ImpactReviewedValueTransferDocument.admitsSorted(
    relation: ImpactReviewedValueRelationDocument.ReorderedElements
): Boolean {
    val proof = binding.authority as? ImpactReviewedValueBindingDocument ?: return false
    val path =
        when (relation.selection) {
            ImpactReviewedCollectionSelectionDocument.ELEMENT ->
                source.valuePath.values.firstOrNull() == ImpactValuePathSegmentDocument.Element &&
                    source.valuePath == target.valuePath
            ImpactReviewedCollectionSelectionDocument.CONTENT ->
                source.valuePath.values.isEmpty() && target.valuePath.values.isEmpty()
        }
    return path &&
        proof.matchesReceiver(source) &&
        admitsCallbackResult(proof) &&
        source.conservesCollectionConditions(target, proof)
}

internal fun ImpactReviewedValueTransferDocument.admitsTupleConstruction(
    relation: ImpactReviewedValueRelationDocument.TupleConstruction
): Boolean {
    val proof = binding.authority as? ImpactReviewedCallValueBindingDocument ?: return false
    return source.matchesOrdinaryInput(proof, relation.input) &&
        target.isOrdinaryResult(proof) &&
        relation.origin.callStack.hasSameCallStackIdentity(source.callStack) &&
        source.conditions.hasSameConditions(target.conditions) &&
        listOf(ImpactValuePathSegmentDocument.PairComponent(relation.origin, relation.slot)) +
            source.valuePath.values == target.valuePath.values
}

internal fun ImpactReviewedValueTransferDocument.admitsTupleProjection(
    relation: ImpactReviewedValueRelationDocument.TupleProjection
): Boolean {
    val proof = binding.authority as? ImpactReviewedCallValueBindingDocument ?: return false
    val receiver = source.role as? ImpactValueRoleDocument.Receiver ?: return false
    return source.matchesOrdinaryOwner(proof) &&
        receiver.kind == ImpactReceiverKindDocument.DISPATCH &&
        receiver.invocation == proof.invocation &&
        target.isOrdinaryResult(proof) &&
        source.valuePath.values.firstOrNull() ==
            ImpactValuePathSegmentDocument.PairComponent(relation.origin, relation.slot) &&
        source.valuePath.values.drop(1) == target.valuePath.values &&
        source.conditions.hasSameConditions(target.conditions)
}

internal fun ImpactReviewedValueTransferDocument.admitsAssociation(
    relation: ImpactReviewedValueRelationDocument.AssociationComponent
): Boolean {
    val proof = binding.authority as? ImpactReviewedValueBindingDocument ?: return false
    val pairSlot =
        when (relation.slot) {
            ImpactMapEntrySlotDocument.KEY -> ImpactPairSlotDocument.FIRST
            ImpactMapEntrySlotDocument.VALUE -> ImpactPairSlotDocument.SECOND
        }
    return relation.entry.matchesReturn(source) &&
        admitsCallbackResult(proof) &&
        source.valuePath.values.firstOrNull() ==
            ImpactValuePathSegmentDocument.PairComponent(relation.entry.tupleOrigin, pairSlot) &&
        listOf(ImpactValuePathSegmentDocument.MapEntryComponent(relation.entry, relation.slot)) +
            source.valuePath.values.drop(1) == target.valuePath.values &&
        source.conservesEntryEvidence(target, relation.entry)
}

internal fun ImpactReviewedMapEntryCorrelationDocument.matchesReturn(
    source: ImpactValueSiteReferenceDocument
): Boolean =
    source.role == ImpactValueRoleDocument.Return &&
        source.range == returnRange &&
        source.enclosing == returnExecution.enclosing &&
        source.executionOwner == returnExecution.executionOwner &&
        callStack.hasSameCallStackIdentity(source.callStack)

private fun ImpactValueSiteReferenceDocument.matchesOrdinaryInput(
    proof: ImpactReviewedCallValueBindingDocument,
    input: ImpactReviewedTupleInputDocument,
): Boolean =
    matchesOrdinaryOwner(proof) &&
        when (input) {
            ImpactReviewedTupleInputDocument.EXTENSION_RECEIVER ->
                when (val receiver = role) {
                    is ImpactValueRoleDocument.Receiver ->
                        receiver.kind == ImpactReceiverKindDocument.EXTENSION && receiver.invocation == proof.invocation
                    else -> false
                }
            ImpactReviewedTupleInputDocument.ARGUMENT_ZERO -> matchesArgument(proof, 0)
            ImpactReviewedTupleInputDocument.ARGUMENT_ONE -> matchesArgument(proof, 1)
        }

private fun ImpactValueSiteReferenceDocument.matchesArgument(
    proof: ImpactReviewedCallValueBindingDocument,
    index: Int,
): Boolean =
    when (val argument = role) {
        is ImpactValueRoleDocument.Argument -> argument.index.value == index && argument.invocation == proof.invocation
        else -> false
    }

private fun ImpactValueSiteReferenceDocument.matchesOrdinaryOwner(
    proof: ImpactReviewedCallValueBindingDocument
): Boolean =
    enclosing == proof.enclosing &&
        executionOwner == proof.invocation.executionOwner &&
        proof.invocation.range.containsExecutionRange(range)

internal fun ImpactValueSiteReferenceDocument.isOrdinaryResult(proof: ImpactReviewedCallValueBindingDocument): Boolean =
    matchesOrdinaryOwner(proof) && role == ImpactValueRoleDocument.ExpressionResult && range == proof.invocation.range

private fun ImpactValueSiteReferenceDocument.conservesEntryEvidence(
    target: ImpactValueSiteReferenceDocument,
    entry: ImpactReviewedMapEntryCorrelationDocument,
): Boolean =
    when (entry.keyEvidence) {
        ImpactReviewedEntryKeyEvidenceDocument.Conditional -> conservesEntryConditions(target, entry)
        is ImpactReviewedEntryKeyEvidenceDocument.Distinct -> conditions.hasSameConditions(target.conditions)
    }
