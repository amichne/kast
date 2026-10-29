package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactReviewedEntrySelectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedInputEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryFamilyDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMappedEntryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedPreservedKeyOriginDocument
import io.github.amichne.kast.relation.contract.ReviewedEntrySelection
import io.github.amichne.kast.relation.contract.ReviewedGroupBucket
import io.github.amichne.kast.relation.contract.ReviewedInputEntryOrigin
import io.github.amichne.kast.relation.contract.ReviewedMapEntryCorrelation
import io.github.amichne.kast.relation.contract.ReviewedMapEntryFamily
import io.github.amichne.kast.relation.contract.ReviewedMapEntryOrigin
import io.github.amichne.kast.relation.contract.ReviewedMappedEntryOrigin
import io.github.amichne.kast.relation.contract.ReviewedPreservedKeyOrigin

internal fun ReviewedMapEntryOrigin.impactDocument(): ImpactProjected<ImpactReviewedMapEntryOriginDocument> =
    when (this) {
        is ReviewedGroupBucket -> impactDocument()
        is ReviewedInputEntryOrigin -> impactDocument()
        is ReviewedPreservedKeyOrigin -> impactDocument()
        is ReviewedMapEntryCorrelation -> impactDocument()
        is ReviewedMappedEntryOrigin -> impactDocument()
    }

internal fun ReviewedMapEntryFamily.impactDocument(): ImpactProjected<ImpactReviewedMapEntryFamilyDocument> =
    binding.impactDocument().impactZip(callStack.impactDocument()).impactMap { (binding, stack) ->
        ImpactReviewedMapEntryFamilyDocument(binding, stack)
    }

internal fun ReviewedMappedEntryOrigin.impactDocument(): ImpactProjected<ImpactReviewedMappedEntryOriginDocument> =
    family
        .impactDocument()
        .impactZip(returnRange.impactDocument())
        .impactZip(returnExecution.impactDocument())
        .impactMap { (source, execution) ->
            ImpactReviewedMappedEntryOriginDocument(source.first, source.second, execution, selection.impactDocument())
        }

internal fun ReviewedInputEntryOrigin.impactDocument(): ImpactProjected<ImpactReviewedInputEntryOriginDocument> =
    family
        .impactDocument()
        .impactZip(receiverRange.impactDocument())
        .impactZip(receiverExecution.impactDocument())
        .impactMap { (source, execution) ->
            ImpactReviewedInputEntryOriginDocument(source.first, source.second, execution, selection.impactDocument())
        }

internal fun ReviewedPreservedKeyOrigin.impactDocument(): ImpactProjected<ImpactReviewedPreservedKeyOriginDocument> =
    family
        .impactDocument()
        .impactZip(receiverRange.impactDocument())
        .impactZip(receiverExecution.impactDocument())
        .impactMap { (source, execution) ->
            ImpactReviewedPreservedKeyOriginDocument(source.first, source.second, execution, selection.impactDocument())
        }

internal fun ReviewedEntrySelection.impactDocument(): ImpactReviewedEntrySelectionDocument =
    when (this) {
        ReviewedEntrySelection.COMPONENT -> ImpactReviewedEntrySelectionDocument.COMPONENT
        ReviewedEntrySelection.CONTENT -> ImpactReviewedEntrySelectionDocument.CONTENT
    }
