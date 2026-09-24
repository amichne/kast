package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInputRejectionEvidence
import io.github.amichne.kast.appserver.query.PublicSourceReadIntent
import io.github.amichne.kast.appserver.query.lower
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceInternalObligation
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceRequestField
import io.github.amichne.kast.protocol.contract.SourceRequestIngress
import io.github.amichne.kast.protocol.contract.SourceRequestPath
import io.github.amichne.kast.protocol.contract.SourceRequestRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val sourceInputJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = false
}

/** Diagnose canonical and hosted source syntax before runtime startup. */
internal fun sourceInputRejectionEvidence(raw: JsonElement): BrokerInputRejectionEvidence.Source =
    BrokerInputRejectionEvidence.Source(
        when (
            val decoded =
                if (raw is JsonObject && (raw["anchor"] as? JsonObject)?.containsKey("symbolRef") == true) {
                    val intent =
                        try {
                            sourceInputJson.decodeFromJsonElement(PublicSourceReadIntent.serializer(), raw)
                        } catch (_: kotlinx.serialization.SerializationException) {
                            null
                        }
                    intent?.lower()
                        ?: Refinement.Rejected(
                            SourceReadFailureDetail.RequestRejected(
                                SourceRequestField(SourceRequestPath.DOCUMENT),
                                SourceRequestRule.INVALID_JSON,
                            )
                        )
                } else SourceRequestIngress.decode(raw, sourceInputJson)
        ) {
            is Refinement.Rejected -> decoded.failure
            is Refinement.Refined ->
                SourceReadFailureDetail.InternalContractFailure(SourceInternalObligation.REQUEST_REFINEMENT)
        }
    )
