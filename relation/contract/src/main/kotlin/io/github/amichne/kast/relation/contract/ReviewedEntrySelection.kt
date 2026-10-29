package io.github.amichne.kast.relation.contract

/** Content dependence never establishes the identity of an individual entry component. */
enum class ReviewedEntrySelection {
    COMPONENT,
    CONTENT,
}

val ReviewedMapEntryOrigin.selection: ReviewedEntrySelection
    get() =
        when (this) {
            is ReviewedGroupBucket -> selection
            is ReviewedMapEntryCorrelation -> ReviewedEntrySelection.COMPONENT
            is ReviewedInputEntryOrigin -> selection
            is ReviewedPreservedKeyOrigin -> selection
            is ReviewedMappedEntryOrigin -> selection
        }

internal fun ValueSite.reviewedEntrySelection(): ReviewedEntrySelection =
    if (valuePath.hasReviewedEntryContent() || conditions.facts.any { it.origin.valuePath.hasReviewedEntryContent() })
        ReviewedEntrySelection.CONTENT
    else ReviewedEntrySelection.COMPONENT

private fun ValueAccessPath.hasReviewedEntryContent(): Boolean = segments.any {
    it is ValuePathSegment.MapEntryComponent && it.entry.selection == ReviewedEntrySelection.CONTENT
}

internal const val REVIEWED_ENTRY_SELECTION_BYTES = 4L
