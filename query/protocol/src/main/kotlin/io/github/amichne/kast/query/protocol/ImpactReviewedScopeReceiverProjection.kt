package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactReviewedCallbackReceiverDocument
import io.github.amichne.kast.relation.contract.ReviewedCallbackReceiver

internal fun ReviewedCallbackReceiver.impactDocument(): ImpactProjected<ImpactReviewedCallbackReceiverDocument> =
    binding.impactDocument().impactZip(declared.impactDocument()).impactZip(type.value.impactText()).impactMap {
        (proof, type) ->
        ImpactReviewedCallbackReceiverDocument(proof.first, proof.second, kind.impactDocument(), type)
    }
