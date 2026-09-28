package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInputRejectionEvidence
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceInternalObligation
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceRequestField
import io.github.amichne.kast.protocol.contract.SourceRequestPath
import io.github.amichne.kast.protocol.contract.SourceRequestRule
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.JsonElement

/** Preserve source's finite refinement failure at the public admission boundary. */
internal fun sourceInputRejectionEvidence(raw: JsonElement): BrokerInputRejectionEvidence.Source =
    BrokerInputRejectionEvidence.Source(
        when (val admitted = PublicToolContract.admit(PublicToolIdentity.READ_SOURCE, raw)) {
            is Refinement.Refined ->
                SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
            is Refinement.Rejected ->
                when (val failure = admitted.failure) {
                    is PublicToolInputFailure.Source -> failure.cause
                    else ->
                        SourceReadFailureDetail.RequestRejected(
                            SourceRequestField(SourceRequestPath.DOCUMENT),
                            SourceRequestRule.INVALID_JSON,
                        )
                }
        }
    )
