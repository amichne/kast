package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInputRejectionEvidence
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceInternalObligation
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceRequestIngress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val sourceInputJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = false
}

/** Diagnose a rejected source input with the same typed ingress used by the CLI. */
internal fun sourceInputRejectionEvidence(raw: JsonElement): BrokerInputRejectionEvidence.Source =
    BrokerInputRejectionEvidence.Source(
        when (val decoded = SourceRequestIngress.decode(raw, sourceInputJson)) {
            is Refinement.Rejected -> decoded.failure
            is Refinement.Refined ->
                SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
        }
    )
