package io.github.amichne.kast.protocol.contract

internal val ImpactReviewedMapEntryOriginDocument.selection: ImpactReviewedEntrySelectionDocument
    get() =
        when (this) {
            is ImpactReviewedGroupBucketDocument -> selection
            is ImpactReviewedMapEntryCorrelationDocument -> ImpactReviewedEntrySelectionDocument.COMPONENT
            is ImpactReviewedInputEntryOriginDocument -> selection
            is ImpactReviewedPreservedKeyOriginDocument -> selection
            is ImpactReviewedMappedEntryOriginDocument -> selection
        }

internal fun ImpactValueSiteReferenceDocument.reviewedEntrySelection(): ImpactReviewedEntrySelectionDocument =
    if (valuePath.hasEntryContent() || conditions.values.any { it.origin.valuePath.hasEntryContent() })
        ImpactReviewedEntrySelectionDocument.CONTENT
    else ImpactReviewedEntrySelectionDocument.COMPONENT

private fun BoundedProtocolList<ImpactValuePathSegmentDocument>.hasEntryContent(): Boolean = values.any {
    it is ImpactValuePathSegmentDocument.MapEntryComponent &&
        it.entry.selection == ImpactReviewedEntrySelectionDocument.CONTENT
}

internal fun ImpactValueSiteReferenceDocument.matchesEntrySelection(
    selection: ImpactReviewedEntrySelectionDocument,
    slot: ImpactMapEntrySlotDocument,
): Boolean {
    if (valuePath.values.isEmpty()) return selection == ImpactReviewedEntrySelectionDocument.CONTENT
    val component = valuePath.values.first() as? ImpactValuePathSegmentDocument.MapEntryComponent ?: return false
    return component.slot == slot && selection == reviewedEntrySelection()
}

internal fun ImpactReviewedEntrySelectionDocument.preservesVisibleContent(
    source: ImpactValueSiteReferenceDocument
): Boolean =
    this == ImpactReviewedEntrySelectionDocument.CONTENT ||
        source.reviewedEntrySelection() == ImpactReviewedEntrySelectionDocument.COMPONENT
