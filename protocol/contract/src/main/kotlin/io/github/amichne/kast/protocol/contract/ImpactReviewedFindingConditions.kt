package io.github.amichne.kast.protocol.contract

/** Compact exact correspondence keeps the premises of each reviewed relationship. */
internal fun ImpactFindingDocument.admitsReviewedPremises(): Boolean {
    val conditions = destination.conditions.values
    if (reviewedRelations.values.isNotEmpty() && !producer.conditions.isPreservedBy(destination.conditions))
        return false
    return reviewedRelations.values.all { it.hasRequiredPremise(conditions) } &&
        representation.hasRequiredPremises(conditions)
}

private fun ImpactFindingRepresentationDocument.hasRequiredPremises(
    conditions: List<ImpactValueConditionDocument>
): Boolean =
    when (this) {
        ImpactFindingRepresentationDocument.NotModeled -> true
        is ImpactFindingRepresentationDocument.Present ->
            branches.values.all { branch ->
                branch.provenance.values.all { it.hasRequiredPremise(conditions) }
            }
    }

private fun ImpactFindingProvenanceDocument.hasRequiredPremise(
    conditions: List<ImpactValueConditionDocument>
): Boolean =
    when (this) {
        is ImpactFindingProvenanceDocument.ReviewedLibrary -> binding.hasRequiredPremise(conditions)
        is ImpactFindingProvenanceDocument.Origin,
        is ImpactFindingProvenanceDocument.ModeledTransfer,
        is ImpactFindingProvenanceDocument.ModeledTransformation,
        is ImpactFindingProvenanceDocument.BoundaryModel,
        ImpactFindingProvenanceDocument.Unmodeled -> true
    }

private fun ImpactReviewedValueRelationshipDocument.hasRequiredPremise(
    conditions: List<ImpactValueConditionDocument>
): Boolean =
    when (val applied = relation) {
        is ImpactReviewedValueRelationDocument.CallbackReceiverInput -> true
        is ImpactReviewedValueRelationDocument.GroupPlacement -> applied.entry.hasGroupPremise(conditions)
        is ImpactReviewedValueRelationDocument.FilteredElements ->
            conditions.any { it is ImpactValueConditionDocument.ReviewedPredicateAccepts && it.binding == authority } &&
                conditions.any {
                    it is ImpactValueConditionDocument.ReviewedCollectionNormalResult && it.binding == authority
                }
        ImpactReviewedValueRelationDocument.NonNullCallbackResult ->
            conditions.any {
                it is ImpactValueConditionDocument.ReviewedCallbackResultNonNull && it.binding == authority
            }
        is ImpactReviewedValueRelationDocument.EntryInput -> true
        is ImpactReviewedValueRelationDocument.PreservedKey ->
            conditions.any {
                it is ImpactValueConditionDocument.ReviewedPreservedKeySurvives && it.entry == applied.entry
            }
        is ImpactReviewedValueRelationDocument.EntryProjection -> admitsEntryPremises(applied, conditions)
        is ImpactReviewedValueRelationDocument.MappedValue ->
            conditions.any { it is ImpactValueConditionDocument.ReviewedMappedKeySurvives && it.entry == applied.entry }
        is ImpactReviewedValueRelationDocument.AssociationComponent -> applied.entry.hasRequiredPremise(conditions)
        is ImpactReviewedValueRelationDocument.ReorderedElements ->
            conditions.any {
                it is ImpactValueConditionDocument.ReviewedCollectionNormalResult && it.binding == authority
            }
        is ImpactReviewedValueRelationDocument.CallbackCorrespondence ->
            applied.semantics != ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT ||
                conditions.any {
                    it is ImpactValueConditionDocument.ReviewedCallbackNormalReturn && it.binding == authority
                }
        is ImpactReviewedValueRelationDocument.TupleConstruction,
        is ImpactReviewedValueRelationDocument.TupleProjection -> true
    }

private fun ImpactReviewedMapEntryCorrelationDocument.hasRequiredPremise(
    conditions: List<ImpactValueConditionDocument>
): Boolean =
    when (keyEvidence) {
        ImpactReviewedEntryKeyEvidenceDocument.Conditional ->
            conditions.any { it is ImpactValueConditionDocument.ReviewedEntryKeyUnique && it.entry == this }
        is ImpactReviewedEntryKeyEvidenceDocument.Distinct -> admitsKeyEvidence()
    }

private fun ImpactReviewedValueRelationshipDocument.admitsEntryPremises(
    applied: ImpactReviewedValueRelationDocument.EntryProjection,
    conditions: List<ImpactValueConditionDocument>,
): Boolean {
    val proof = authority as? ImpactReviewedCallValueBindingDocument ?: return false
    return conditions.any {
        it is ImpactValueConditionDocument.ReviewedEntryComponentStable &&
            it.binding == proof &&
            it.entry == applied.entry &&
            it.slot == applied.slot
    } &&
        (!proof.needsEntryPrefixPremise() ||
            conditions.any {
                it is ImpactValueConditionDocument.ReviewedEntryPrefixNormal &&
                    it.binding == proof &&
                    it.entry == applied.entry &&
                    it.slot == applied.slot
            })
}
