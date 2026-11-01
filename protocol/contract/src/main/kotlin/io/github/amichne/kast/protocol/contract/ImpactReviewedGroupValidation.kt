package io.github.amichne.kast.protocol.contract

internal val ImpactReviewedGroupBucketDocument.slot: ImpactMapEntrySlotDocument
    get() =
        if (placement == ImpactReviewedGroupPlacementDocument.KEY_CONTENT) ImpactMapEntrySlotDocument.KEY
        else ImpactMapEntrySlotDocument.VALUE

internal fun ImpactReviewedGroupBucketDocument.matchesBinding(binding: ImpactReviewedValueBindingDocument): Boolean =
    when (placement) {
        ImpactReviewedGroupPlacementDocument.ORIGINAL_ELEMENTS,
        ImpactReviewedGroupPlacementDocument.KEY_CONTENT -> callbacks.selector == binding
        ImpactReviewedGroupPlacementDocument.TRANSFORMED_ELEMENTS ->
            callbacks is ImpactReviewedGroupCallbacksDocument.TransformedElements && callbacks.transform == binding
    }

internal fun ImpactReviewedGroupBucketDocument.admits(basis: ImpactSemanticBasisDocument): Boolean =
    callbacks.admits(basis) &&
        sourceExecution.enclosing == callbacks.selector.enclosing &&
        sourceExecution.executionOwner.admits(sourceExecution.enclosing, sourceRange) &&
        callStack.admitsReviewedStack(basis) &&
        admitsPlacementAnchor()

private fun ImpactReviewedGroupCallbacksDocument.admits(basis: ImpactSemanticBasisDocument): Boolean {
    if (selector.basis != basis || !selector.admits(selector.enclosing)) return false
    return when (this) {
        is ImpactReviewedGroupCallbacksDocument.OriginalElements ->
            selector.rule == ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_V1
        is ImpactReviewedGroupCallbacksDocument.TransformedElements -> admitsTransform()
    }
}

private fun ImpactReviewedGroupCallbacksDocument.TransformedElements.admitsTransform(): Boolean {
    if (
        selector.rule != ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ITERABLE_GROUP_BY_TRANSFORM_V1 ||
            transform.rule != selector.rule
    )
        return false
    if (selector.selected.formalIndex.value != 0 || transform.selected.formalIndex.value != 1) return false
    if (!sameInvocation() || !transform.admits(selector.enclosing) || selector.body == transform.body) return false
    return selector.body.range.end.value <= transform.body.range.start.value ||
        transform.body.range.end.value <= selector.body.range.start.value
}

private fun ImpactReviewedGroupCallbacksDocument.TransformedElements.sameInvocation(): Boolean =
    selector.basis == transform.basis &&
        selector.enclosing == transform.enclosing &&
        selector.selected.sameGroupTarget(transform.selected) &&
        selector.supplierRange == transform.supplierRange &&
        selector.artifacts == transform.artifacts &&
        selector.parent == transform.parent

private fun ImpactReviewedFunctionDocument.sameGroupTarget(other: ImpactReviewedFunctionDocument): Boolean =
    declaration == other.declaration &&
        qualifiedIdentity == other.qualifiedIdentity &&
        receiver == other.receiver &&
        contextReceivers == other.contextReceivers &&
        valueParameters == other.valueParameters &&
        typeParameterCount == other.typeParameterCount

private fun ImpactReviewedGroupBucketDocument.admitsPlacementAnchor(): Boolean =
    when (placement) {
        ImpactReviewedGroupPlacementDocument.ORIGINAL_ELEMENTS ->
            callbacks is ImpactReviewedGroupCallbacksDocument.OriginalElements &&
                sourceExecution.executionOwner == ImpactExecutionOwnerDocument.Declaration &&
                callbacks.selector.supplierRange.containsExecutionRange(sourceRange) &&
                sourceRange.end.value <= callbacks.selector.body.range.start.value
        ImpactReviewedGroupPlacementDocument.TRANSFORMED_ELEMENTS ->
            callbacks is ImpactReviewedGroupCallbacksDocument.TransformedElements &&
                admitsReturnAnchor(callbacks.transform)
        ImpactReviewedGroupPlacementDocument.KEY_CONTENT ->
            selection == ImpactReviewedEntrySelectionDocument.CONTENT && admitsReturnAnchor(callbacks.selector)
    }

private fun ImpactReviewedGroupBucketDocument.admitsReturnAnchor(binding: ImpactReviewedValueBindingDocument): Boolean =
    sourceExecution.executionOwner == ImpactExecutionOwnerDocument.ReviewedCallback(binding) &&
        binding.body.range.containsExecutionRange(sourceRange)

private fun ImpactReviewedGroupBucketDocument.matchesSource(source: ImpactValueSiteReferenceDocument): Boolean =
    sourceRange == source.range &&
        sourceExecution == ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner) &&
        callStack.hasSameCallStackIdentity(source.callStack) &&
        when (placement) {
            ImpactReviewedGroupPlacementDocument.ORIGINAL_ELEMENTS ->
                callbacks.selector.matchesReceiver(source) &&
                    (source.valuePath.values.isEmpty() ||
                        source.valuePath.values.first() == ImpactValuePathSegmentDocument.Element)
            ImpactReviewedGroupPlacementDocument.TRANSFORMED_ELEMENTS,
            ImpactReviewedGroupPlacementDocument.KEY_CONTENT -> source.role == ImpactValueRoleDocument.Return
        }

private fun ImpactReviewedGroupBucketDocument.sourceSelection(
    source: ImpactValueSiteReferenceDocument
): ImpactReviewedEntrySelectionDocument =
    if (
        placement == ImpactReviewedGroupPlacementDocument.KEY_CONTENT ||
            placement == ImpactReviewedGroupPlacementDocument.ORIGINAL_ELEMENTS && source.valuePath.values.isEmpty()
    )
        ImpactReviewedEntrySelectionDocument.CONTENT
    else source.reviewedEntrySelection()

internal fun ImpactReviewedValueTransferDocument.admitsGroup(
    relation: ImpactReviewedValueRelationDocument.GroupPlacement
): Boolean {
    val entry = relation.entry
    val proof = binding.authority as? ImpactReviewedValueBindingDocument ?: return false
    if (
        !entry.matchesSource(source) || entry.selection != entry.sourceSelection(source) || !admitsCallbackResult(proof)
    )
        return false
    val suffix =
        if (entry.placement == ImpactReviewedGroupPlacementDocument.ORIGINAL_ELEMENTS) source.valuePath.values.drop(1)
        else source.valuePath.values
    val prefix =
        listOf(ImpactValuePathSegmentDocument.MapEntryComponent(entry, entry.slot)) +
            if (entry.slot == ImpactMapEntrySlotDocument.VALUE) listOf(ImpactValuePathSegmentDocument.Element)
            else emptyList()
    return prefix + suffix == target.valuePath.values && source.conservesGroupConditions(target, entry)
}

internal fun ImpactValueConditionDocument.ReviewedGroupMembership.admitsGroupMembership(): Boolean =
    entry.slot == ImpactMapEntrySlotDocument.VALUE && admitsGroupPremise(entry)

internal fun ImpactValueConditionDocument.ReviewedGroupKeyRepresentative.admitsGroupRepresentative(): Boolean =
    entry.slot == ImpactMapEntrySlotDocument.KEY && admitsGroupPremise(entry)

private fun ImpactValueConditionDocument.admitsGroupPremise(entry: ImpactReviewedGroupBucketDocument): Boolean =
    entry.admits(origin.execution.enclosing.basis) &&
        entry.matchesSource(sourceSite()) &&
        entry.selection.preservesVisibleContent(sourceSite()) &&
        (entry.sourceSelection(sourceSite()) != ImpactReviewedEntrySelectionDocument.CONTENT ||
            entry.selection == ImpactReviewedEntrySelectionDocument.CONTENT) &&
        useRange == entry.callbacks.selector.supplierRange

private fun ImpactValueSiteReferenceDocument.conservesGroupConditions(
    target: ImpactValueSiteReferenceDocument,
    entry: ImpactReviewedGroupBucketDocument,
): Boolean {
    if (
        target.conditions.values.size != conditions.values.size + 1 ||
            conditions.values != target.conditions.values.dropLast(1)
    )
        return false
    val added = target.conditions.values.last()
    val sameEntry =
        when (added) {
            is ImpactValueConditionDocument.ReviewedGroupMembership ->
                entry.slot == ImpactMapEntrySlotDocument.VALUE && added.entry == entry
            is ImpactValueConditionDocument.ReviewedGroupKeyRepresentative ->
                entry.slot == ImpactMapEntrySlotDocument.KEY && added.entry == entry
            else -> false
        }
    return sameEntry &&
        added.useRange == target.range &&
        added.sourceSite().hasSameValueIdentity(copy(conditions = EmptyImpactConditions))
}

internal fun ImpactReviewedGroupBucketDocument.hasGroupPremise(
    conditions: List<ImpactValueConditionDocument>
): Boolean = conditions.any { condition ->
    when (condition) {
        is ImpactValueConditionDocument.ReviewedGroupMembership ->
            slot == ImpactMapEntrySlotDocument.VALUE && condition.entry == this
        is ImpactValueConditionDocument.ReviewedGroupKeyRepresentative ->
            slot == ImpactMapEntrySlotDocument.KEY && condition.entry == this
        else -> false
    }
}
