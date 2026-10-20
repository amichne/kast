package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.relation.contract.ValueReviewedCallbackTarget

internal fun ValueReviewedCallbackTarget.impactReviewedCallbackDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactCallTargetDocument.ReviewedCallback> =
    binding
        .impactDocument(references)
        .impactZip(argument.impactImplementationOccurrence())
        .impactZip(formal.impactDocument())
        .impactZip(formal.callable.impactModelEndpoint())
        .impactZip(formal.executionOwner.impactModelExecution(references))
        .impactMap { (formal, execution) ->
            val (proof, callable) = formal
            val (binding, parameter) = proof
            ImpactCallTargetDocument.ReviewedCallback(
                binding.first,
                binding.second,
                parameter,
                callable,
                execution,
            )
        }
