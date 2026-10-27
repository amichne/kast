package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCorrespondenceFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactPathClassificationDocument
import io.github.amichne.kast.protocol.contract.ImpactRelationshipDocument
import io.github.amichne.kast.query.contract.QueryImpactClassification
import io.github.amichne.kast.query.contract.QueryImpactCorrespondenceFailure
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRelationship

internal fun QueryImpactPath.classificationDocument(): ImpactProjected<ImpactPathClassificationDocument> =
    when (val evidence = classification) {
        is QueryImpactClassification.Established ->
            Refinement.Refined(
                ImpactPathClassificationDocument.Established(
                    when (evidence.relationship) {
                        QueryImpactRelationship.EXACT -> ImpactRelationshipDocument.EXACT
                        QueryImpactRelationship.DERIVED -> ImpactRelationshipDocument.DERIVED
                    }
                )
            )
        is QueryImpactClassification.NotEstablished ->
            steps.indexOf(evidence.step).impactOffset().impactMap { index ->
                ImpactPathClassificationDocument.NotEstablished(
                    index,
                    when (evidence.cause) {
                        QueryImpactCorrespondenceFailure.REPRESENTATION_MODEL_ONLY ->
                            ImpactCorrespondenceFailureDocument.REPRESENTATION_MODEL_ONLY
                        QueryImpactCorrespondenceFailure.BOUNDARY_MODEL_ONLY ->
                            ImpactCorrespondenceFailureDocument.BOUNDARY_MODEL_ONLY
                    },
                )
            }
    }
