package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactReviewedGroupBucketDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedGroupCallbacksDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedGroupPlacementDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.relation.contract.ReviewedGroupBucket
import io.github.amichne.kast.relation.contract.ReviewedGroupCallbacks
import io.github.amichne.kast.relation.contract.ReviewedGroupKeyRepresentative
import io.github.amichne.kast.relation.contract.ReviewedGroupMembership
import io.github.amichne.kast.relation.contract.ReviewedGroupPlacement

internal fun ReviewedGroupBucket.impactDocument(): ImpactProjected<ImpactReviewedGroupBucketDocument> =
    callbacks
        .impactDocument()
        .impactZip(sourceRange.impactDocument())
        .impactZip(sourceExecution.impactDocument())
        .impactZip(callStack.impactDocument())
        .impactMap { (source, stack) ->
            ImpactReviewedGroupBucketDocument(
                source.first.first,
                placement.impactDocument(),
                selection.impactDocument(),
                source.first.second,
                source.second,
                stack,
            )
        }

private fun ReviewedGroupCallbacks.impactDocument(): ImpactProjected<ImpactReviewedGroupCallbacksDocument> =
    when (this) {
        is ReviewedGroupCallbacks.OriginalElements ->
            selector.impactDocument().impactMap { ImpactReviewedGroupCallbacksDocument.OriginalElements(it) }
        is ReviewedGroupCallbacks.TransformedElements ->
            selector.impactDocument().impactZip(transform.impactDocument()).impactMap { (selector, transform) ->
                ImpactReviewedGroupCallbacksDocument.TransformedElements(selector, transform)
            }
    }

private fun ReviewedGroupPlacement.impactDocument(): ImpactReviewedGroupPlacementDocument =
    when (this) {
        ReviewedGroupPlacement.ORIGINAL_ELEMENTS -> ImpactReviewedGroupPlacementDocument.ORIGINAL_ELEMENTS
        ReviewedGroupPlacement.TRANSFORMED_ELEMENTS -> ImpactReviewedGroupPlacementDocument.TRANSFORMED_ELEMENTS
        ReviewedGroupPlacement.KEY_CONTENT -> ImpactReviewedGroupPlacementDocument.KEY_CONTENT
    }

internal fun ReviewedGroupMembership.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedGroupMembership> =
    origin.impactDocument().impactZip(useRange.impactDocument()).impactZip(entry.impactDocument()).impactMap {
        (source, entry) ->
        ImpactValueConditionDocument.ReviewedGroupMembership(source.first, source.second, entry)
    }

internal fun ReviewedGroupKeyRepresentative.impactDocument():
    ImpactProjected<ImpactValueConditionDocument.ReviewedGroupKeyRepresentative> =
    origin.impactDocument().impactZip(useRange.impactDocument()).impactZip(entry.impactDocument()).impactMap {
        (source, entry) ->
        ImpactValueConditionDocument.ReviewedGroupKeyRepresentative(source.first, source.second, entry)
    }
