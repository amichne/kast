@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackCallableWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowWireDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedUnavailableCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceWireDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.WireDocumentConversion
import io.github.amichne.kast.protocol.contract.callbackWire
import io.github.amichne.kast.protocol.contract.combineConverted
import io.github.amichne.kast.protocol.contract.flatMapConverted
import io.github.amichne.kast.protocol.contract.flattenConverted
import io.github.amichne.kast.protocol.contract.mapConverted
import io.github.amichne.kast.protocol.contract.toContract
import io.github.amichne.kast.protocol.contract.toWireDocument
import io.github.amichne.kast.protocol.contract.toWireDocumentConversion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackNamedPolicyWireDocument {
    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: QueryCallbackNamedUnavailableCauseDocument) : QueryCallbackNamedPolicyWireDocument

    @Serializable @SerialName("ADMITTED_INLINE") data object AdmittedInline : QueryCallbackNamedPolicyWireDocument

    @Serializable @SerialName("ADMITTED_DIRECT") data object AdmittedDirect : QueryCallbackNamedPolicyWireDocument

    @Serializable
    @SerialName("EXCLUDED")
    data class Excluded(
        val reason: QueryCallbackExclusionReasonDocument,
        @SerialName("excluded_boundary") val excludedBoundary: RelationOccurrenceWireDocument,
    ) : QueryCallbackNamedPolicyWireDocument
}

@Serializable
internal data class QueryCallbackObservationWireDocument(
    val occurrence: RelationOccurrenceWireDocument,
    val target: QueryCallbackCallableWireDocument,
    @SerialName("lexical_owner") val lexicalOwner: QueryCallbackCallableWireDocument,
    @SerialName("callback_body") val callbackBody: RelationOccurrenceWireDocument,
    @SerialName("named_policy") val namedPolicy: QueryCallbackNamedPolicyWireDocument,
    @Serializable(with = io.github.amichne.kast.protocol.contract.QueryCallbackFlowWireSerializer::class)
    val flow: QueryCallbackFlowWireDocument,
    val relation: RelationKindDocument,
    @SerialName("requested_domain") val requestedDomain: QueryRelationRequestedDomainDocument,
    @SerialName("effective_domain") val effectiveDomain: QueryRelationDomainDocument,
    @SerialName("domain_fingerprint") val domainFingerprint: QueryRelationDomainFingerprint,
    @SerialName("result_projection") val resultProjection: QueryCallbackResultProjectionWireDocument,
    val basis: ImpactSemanticBasisDocument,
)

@Serializable
internal data class QueryWalkCallbackObservationWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    val depth: Int,
    val observation: QueryCallbackObservationWireDocument,
)

internal fun QueryCallbackObservationDocument.toWireDocument() =
    QueryCallbackObservationWireDocument(
        occurrence.callbackWire(),
        target.callbackWire(),
        lexicalOwner.callbackWire(),
        callbackBody.callbackWire(),
        when (val policy = namedPolicy) {
            is QueryCallbackNamedPolicyDocument.Unavailable ->
                QueryCallbackNamedPolicyWireDocument.Unavailable(policy.cause)
            QueryCallbackNamedPolicyDocument.AdmittedInline -> QueryCallbackNamedPolicyWireDocument.AdmittedInline
            QueryCallbackNamedPolicyDocument.AdmittedDirect -> QueryCallbackNamedPolicyWireDocument.AdmittedDirect
            is QueryCallbackNamedPolicyDocument.Excluded ->
                QueryCallbackNamedPolicyWireDocument.Excluded(policy.reason, policy.excludedBoundary.callbackWire())
        },
        flow.callbackWire(),
        relation,
        requestedDomain,
        effectiveDomain,
        domainFingerprint,
        resultProjection.resultProjectionWire(),
        basis,
    )

internal fun QueryWalkCallbackObservationDocument.toWireDocument() =
    QueryWalkCallbackObservationWireDocument(
        QueryReferenceWireDocument.ExactSymbol(subject.token.value),
        depth.value,
        observation.toWireDocument(),
    )

internal fun QueryCallbackObservationWireDocument.toContract():
    WireDocumentConversion<QueryCallbackObservationDocument> =
    occurrence.toContract().flatMapConverted { occurrence ->
        target.toContract().flatMapConverted { target ->
            lexicalOwner.toContract().flatMapConverted { owner ->
                callbackBody.toContract().flatMapConverted { body ->
                    val policy: WireDocumentConversion<QueryCallbackNamedPolicyDocument> =
                        when (val policy = namedPolicy) {
                            is QueryCallbackNamedPolicyWireDocument.Unavailable ->
                                WireDocumentConversion.Converted(
                                    QueryCallbackNamedPolicyDocument.Unavailable(policy.cause)
                                )
                            QueryCallbackNamedPolicyWireDocument.AdmittedInline ->
                                WireDocumentConversion.Converted(QueryCallbackNamedPolicyDocument.AdmittedInline)
                            QueryCallbackNamedPolicyWireDocument.AdmittedDirect ->
                                WireDocumentConversion.Converted(QueryCallbackNamedPolicyDocument.AdmittedDirect)
                            is QueryCallbackNamedPolicyWireDocument.Excluded ->
                                policy.excludedBoundary.toContract().mapConverted {
                                    QueryCallbackNamedPolicyDocument.Excluded(policy.reason, it)
                                }
                        }
                    combineConverted(policy, flow.toContract(), resultProjection.toContract()) {
                            policy,
                            flow,
                            resultProjection ->
                            QueryCallbackObservationDocument.create(
                                    occurrence,
                                    target,
                                    owner,
                                    body,
                                    policy,
                                    flow,
                                    relation,
                                    requestedDomain,
                                    effectiveDomain,
                                    domainFingerprint,
                                    resultProjection,
                                    basis,
                                )
                                .toWireDocumentConversion()
                        }
                        .flattenConverted()
                }
            }
        }
    }

internal fun QueryWalkCallbackObservationWireDocument.toContract():
    WireDocumentConversion<QueryWalkCallbackObservationDocument> =
    combineConverted(
            ProtocolText.parse(subject.token).toWireDocumentConversion(),
            TraversalDepthDocument.parse(depth).toWireDocumentConversion(),
            observation.toContract(),
        ) { token, depth, observation ->
            QueryWalkCallbackObservationDocument(QueryReferenceDocument.ExactSymbol(token), depth, observation)
        }
        .flatMapConverted { value ->
            if (value.observation.resultProjection.admitsSubject(value.subject)) WireDocumentConversion.Converted(value)
            else WireDocumentConversion.Rejected
        }
