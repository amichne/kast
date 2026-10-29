package io.github.amichne.kast.protocol.contract

internal fun ImpactReviewedValueRelationshipDocument.admits(host: ImpactDeclarationReferenceDocument): Boolean =
    when (val proof = authority) {
        is ImpactReviewedValueBindingDocument -> proof.admits(host) && admitsCallbackRelation(proof)
        is ImpactReviewedCallValueBindingDocument -> proof.admits(host) && admitsCallRelation(proof)
    }

internal fun ImpactReviewedValueRelationshipDocument.matchesOwner(owner: ImpactReviewedValueBindingDocument): Boolean =
    authority == owner && owner.admits(owner.enclosing)

private fun ImpactReviewedValueRelationshipDocument.admitsCallbackRelation(
    proof: ImpactReviewedValueBindingDocument
): Boolean =
    when (val applied = relation) {
        is ImpactReviewedValueRelationDocument.CallbackReceiverInput ->
            proof == applied.receiver.binding && applied.receiver.admits(proof.enclosing)
        is ImpactReviewedValueRelationDocument.GroupPlacement ->
            applied.entry.matchesBinding(proof) && applied.entry.admits(proof.basis)
        is ImpactReviewedValueRelationDocument.FilteredElements ->
            proof.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_FILTER_V1
        ImpactReviewedValueRelationDocument.NonNullCallbackResult ->
            proof.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1
        is ImpactReviewedValueRelationDocument.EntryInput ->
            proof == applied.entry.family.binding && applied.entry.admits(proof.basis)
        is ImpactReviewedValueRelationDocument.PreservedKey ->
            proof == applied.entry.family.binding && applied.entry.admits(proof.basis)
        is ImpactReviewedValueRelationDocument.EntryProjection -> false
        is ImpactReviewedValueRelationDocument.MappedValue ->
            proof == applied.entry.binding && applied.entry.admits(proof.basis)
        is ImpactReviewedValueRelationDocument.CallbackCorrespondence ->
            when (applied.semantics) {
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_INPUT_ELEMENT_TO_CALLBACK_PARAMETER,
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER ->
                    proof.rule.isReviewedCollection()
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT ->
                    proof.rule.isReviewedMap()
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT ->
                    proof.rule.isReviewedDeclaredReturn()
                ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT ->
                    proof.rule.isReviewedReceiverResult()
            }
        is ImpactReviewedValueRelationDocument.ReorderedElements ->
            proof.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1
        is ImpactReviewedValueRelationDocument.AssociationComponent ->
            proof == applied.entry.binding && applied.entry.admits(proof.basis)
        is ImpactReviewedValueRelationDocument.TupleConstruction,
        is ImpactReviewedValueRelationDocument.TupleProjection -> false
    }

private fun ImpactReviewedValueRelationshipDocument.admitsCallRelation(
    proof: ImpactReviewedCallValueBindingDocument
): Boolean =
    when (val applied = relation) {
        is ImpactReviewedValueRelationDocument.CallbackReceiverInput -> false
        is ImpactReviewedValueRelationDocument.GroupPlacement -> false
        is ImpactReviewedValueRelationDocument.FilteredElements,
        ImpactReviewedValueRelationDocument.NonNullCallbackResult -> false
        is ImpactReviewedValueRelationDocument.EntryInput,
        is ImpactReviewedValueRelationDocument.PreservedKey -> false
        is ImpactReviewedValueRelationDocument.EntryProjection ->
            applied.entry.admitsSlot(proof.basis, applied.slot) && proof.rule.admitsEntrySlot(applied.slot)
        is ImpactReviewedValueRelationDocument.MappedValue -> false
        is ImpactReviewedValueRelationDocument.TupleConstruction ->
            applied.origin.binding == proof &&
                applied.origin.admits(proof.basis) &&
                proof.rule.admitsTupleInput(applied.input, applied.slot)
        is ImpactReviewedValueRelationDocument.TupleProjection ->
            applied.origin.admits(proof.basis) &&
                when (applied.slot) {
                    ImpactPairSlotDocument.FIRST ->
                        proof.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_FIRST_V1
                    ImpactPairSlotDocument.SECOND ->
                        proof.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_SECOND_V1
                }
        is ImpactReviewedValueRelationDocument.CallbackCorrespondence,
        is ImpactReviewedValueRelationDocument.ReorderedElements,
        is ImpactReviewedValueRelationDocument.AssociationComponent -> false
    }

private fun ImpactReviewedValueRuleDocument.admitsTupleInput(
    input: ImpactReviewedTupleInputDocument,
    slot: ImpactPairSlotDocument,
): Boolean =
    when (this) {
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_TO_V1 ->
            when (input) {
                ImpactReviewedTupleInputDocument.EXTENSION_RECEIVER -> slot == ImpactPairSlotDocument.FIRST
                ImpactReviewedTupleInputDocument.ARGUMENT_ZERO -> slot == ImpactPairSlotDocument.SECOND
                ImpactReviewedTupleInputDocument.ARGUMENT_ONE -> false
            }
        ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_PAIR_CONSTRUCTOR_V1 ->
            when (input) {
                ImpactReviewedTupleInputDocument.EXTENSION_RECEIVER -> false
                ImpactReviewedTupleInputDocument.ARGUMENT_ZERO -> slot == ImpactPairSlotDocument.FIRST
                ImpactReviewedTupleInputDocument.ARGUMENT_ONE -> slot == ImpactPairSlotDocument.SECOND
            }
        else -> false
    }

private fun ImpactReviewedValueRuleDocument.isReviewedMap(): Boolean =
    this == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_V1 ||
        this == ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_ITERABLE_MAP_V1

private fun ImpactReviewedValueRuleDocument.isReviewedCollection(): Boolean =
    isReviewedMap() ||
        this == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_SORTED_BY_V1 ||
        this == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_ASSOCIATE_V1 ||
        this == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_FILTER_V1 ||
        this == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_MAP_NOT_NULL_V1 ||
        this == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1 ||
        this == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1
