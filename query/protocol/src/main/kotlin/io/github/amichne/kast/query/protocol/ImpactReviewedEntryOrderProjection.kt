package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryComponentInvocationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryReadOrderDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryReadPrefixDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedOrdinaryTargetDocument
import io.github.amichne.kast.relation.contract.ReviewedEntryComponentInvocation
import io.github.amichne.kast.relation.contract.ReviewedEntryReadOrder
import io.github.amichne.kast.relation.contract.ReviewedEntryReadPrefix

internal fun ReviewedEntryReadOrder.impactDocument(): ImpactProjected<ImpactReviewedEntryReadOrderDocument> =
    when (this) {
        ReviewedEntryReadOrder.Direct -> Refinement.Refined(ImpactReviewedEntryReadOrderDocument.Direct)
        is ReviewedEntryReadOrder.Destructured ->
            declarationRange.impactDocument().impactZip(prefix.impactDocument()).impactMap { (range, prefix) ->
                ImpactReviewedEntryReadOrderDocument.Destructured(range, prefix)
            }
    }

private fun ReviewedEntryReadPrefix.impactDocument(): ImpactProjected<ImpactReviewedEntryReadPrefixDocument> =
    when (this) {
        ReviewedEntryReadPrefix.Empty -> Refinement.Refined(ImpactReviewedEntryReadPrefixDocument.Empty)
        is ReviewedEntryReadPrefix.FirstComponent ->
            invocation.impactDocument().impactMap { ImpactReviewedEntryReadPrefixDocument.FirstComponent(it) }
    }

private fun ReviewedEntryComponentInvocation.impactDocument():
    ImpactProjected<ImpactReviewedEntryComponentInvocationDocument> =
    invocation
        .impactDocument()
        .impactZip(selected.target.impactCallableTarget(invocation.basis))
        .impactZip(artifact.impactDocument())
        .impactThen { (selected, artifacts) ->
            when (artifacts) {
                is ImpactReviewedMapArtifactsDocument ->
                    Refinement.Refined(
                        ImpactReviewedEntryComponentInvocationDocument(
                            selected.first,
                            ImpactReviewedOrdinaryTargetDocument.Function(selected.second),
                            artifacts,
                        )
                    )
                else -> Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
            }
        }
