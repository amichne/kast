package io.github.amichne.kast.protocol.contract

/** Compact exact correspondence keeps the premises of each reviewed relationship. */
internal fun ImpactFindingDocument.admitsReviewedPremises(): Boolean {
    val conditions = destination.conditions.values
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
