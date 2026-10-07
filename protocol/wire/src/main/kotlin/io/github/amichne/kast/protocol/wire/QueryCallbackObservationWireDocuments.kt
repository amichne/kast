@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackCallableDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedUnavailableCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data class QueryCallbackCallableWireDocument(
    val declaration: RelationOccurrenceWireDocument,
    @SerialName("compiler_target") val compilerTarget: QueryExcludedCompilerTargetWireDocument,
)

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
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackBodyWireDocument {
    @Serializable
    @SerialName("NAMED")
    data class Named(val callable: QueryCallbackCallableWireDocument) : QueryCallbackBodyWireDocument

    @Serializable
    @SerialName("ANONYMOUS")
    data class Anonymous(
        val occurrence: RelationOccurrenceWireDocument,
        @SerialName("compiler_evidence") val compilerEvidence: CompilerSymbolEvidenceWireDocument,
    ) : QueryCallbackBodyWireDocument
}

@Serializable
internal data class QueryCallbackInvocationWireDocument(
    val occurrence: RelationOccurrenceWireDocument,
    val owner: QueryCallbackBodyWireDocument,
    @SerialName("callable_transfers") val callableTransfers: List<ImpactCompilerTransferDocument>,
    @ProtocolCollectionConstraint(maximumItems = 1000) val forwardings: List<QueryCallbackForwardingWireDocument>,
)

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackFlowWireDocument {
    @Serializable
    @SerialName("IMMUTABLE")
    data class Immutable(val flow: QueryImmutableCallbackFlowWireDocument) : QueryCallbackFlowWireDocument

    @Serializable
    @SerialName("OBSERVED")
    data class Observed(
        val basis: ImpactSemanticBasisDocument,
        val body: QueryCallbackBodyWireDocument.Anonymous,
        val binding: QueryCallbackBindingWireDocument,
        val invocations: List<QueryCallbackInvocationWireDocument>,
        val obligations: List<QueryCallbackFlowCauseDocument>,
        @SerialName("owner_bindings") val ownerBindings: List<QueryCallbackBodyBindingWireDocument>,
        val scan: QueryCallbackInvocationScanDocument,
        val forwarding: QueryCallbackForwardingEvidenceWireDocument,
    ) : QueryCallbackFlowWireDocument

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: QueryCallbackFlowCauseDocument) : QueryCallbackFlowWireDocument

    @Serializable
    @SerialName("CONTRACT_REJECTED")
    data class ContractRejected(val cause: QueryCallbackFlowFailureDocument) : QueryCallbackFlowWireDocument
}

@Serializable
internal data class QueryCallbackObservationWireDocument(
    val occurrence: RelationOccurrenceWireDocument,
    val target: QueryCallbackCallableWireDocument,
    @SerialName("lexical_owner") val lexicalOwner: QueryCallbackCallableWireDocument,
    @SerialName("callback_body") val callbackBody: RelationOccurrenceWireDocument,
    @SerialName("named_policy") val namedPolicy: QueryCallbackNamedPolicyWireDocument,
    val flow: QueryCallbackFlowWireDocument,
    val relation: RelationKindDocument,
    @SerialName("requested_domain") val requestedDomain: QueryRelationRequestedDomainDocument,
    @SerialName("effective_domain") val effectiveDomain: QueryRelationDomainDocument,
    @SerialName("domain_fingerprint") val domainFingerprint: QueryRelationDomainFingerprint,
)

@Serializable
internal data class QueryWalkCallbackObservationWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    val depth: Int,
    val observation: QueryCallbackObservationWireDocument,
)

internal fun RelationOccurrenceDocument.callbackWire() =
    RelationOccurrenceWireDocument(candidateSelector.value, file.value, range.toWireDocument())

internal fun QueryCallbackCallableDocument.callbackWire() =
    QueryCallbackCallableWireDocument(
        declaration.callbackWire(),
        QueryExcludedCompilerTargetWireDocument(
            compilerTarget.file.value,
            compilerTarget.range.toWireDocument(),
            compilerTarget.name.value,
            compilerTarget.kind.toWireDocument(),
            compilerTarget.compilerEvidence.toWireDocument(),
        ),
    )

internal fun QueryCallbackBodyDocument.Anonymous.callbackWire() =
    QueryCallbackBodyWireDocument.Anonymous(occurrence.callbackWire(), compilerEvidence.toWireDocument())

internal fun QueryCallbackBodyDocument.callbackWire(): QueryCallbackBodyWireDocument =
    when (this) {
        is QueryCallbackBodyDocument.Named -> QueryCallbackBodyWireDocument.Named(callable.callbackWire())
        is QueryCallbackBodyDocument.Anonymous -> callbackWire()
    }

private fun QueryCallbackFlowDocument.callbackWire(): QueryCallbackFlowWireDocument =
    when (this) {
        is QueryCallbackFlowDocument.Immutable -> QueryCallbackFlowWireDocument.Immutable(flow.immutableFlowWire())
        is QueryCallbackFlowDocument.Observed ->
            QueryCallbackFlowWireDocument.Observed(
                basis,
                body.callbackWire(),
                binding.callbackWire(),
                invocations.values.map {
                    QueryCallbackInvocationWireDocument(
                        it.occurrence.callbackWire(),
                        it.owner.callbackWire(),
                        it.callableTransfers.values,
                        it.forwardings.values.map { forwarding -> forwarding.callbackWire() },
                    )
                },
                obligations.values,
                ownerBindings.values.map { it.callbackWire() },
                scan,
                forwarding.callbackWire(),
            )
        is QueryCallbackFlowDocument.Unavailable -> QueryCallbackFlowWireDocument.Unavailable(cause)
        is QueryCallbackFlowDocument.ContractRejected -> QueryCallbackFlowWireDocument.ContractRejected(cause)
    }

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
    )

internal fun QueryWalkCallbackObservationDocument.toWireDocument() =
    QueryWalkCallbackObservationWireDocument(
        QueryReferenceWireDocument.ExactSymbol(subject.token.value),
        depth.value,
        observation.toWireDocument(),
    )

internal fun QueryCallbackCallableWireDocument.toContract(): WireDocumentConversion<QueryCallbackCallableDocument> =
    declaration.toContract().flatMapConverted { declaration ->
        combineConverted(
                ProtocolText.parse(compilerTarget.file).toWireDocumentConversion(),
                compilerTarget.range.toContract(),
                ProtocolText.parse(compilerTarget.name).toWireDocumentConversion(),
                compilerTarget.compilerEvidence.toContract(),
            ) { file, range, name, proof ->
                QueryExcludedCompilerTargetDocument.create(file, range, name, compilerTarget.kind.toContract(), proof)
                    .toWireDocumentConversion()
            }
            .flattenConverted()
            .mapConverted { QueryCallbackCallableDocument(declaration, it) }
    }

internal fun QueryCallbackBodyWireDocument.Anonymous.toContract():
    WireDocumentConversion<QueryCallbackBodyDocument.Anonymous> =
    combineConverted(occurrence.toContract(), compilerEvidence.toContract()) { occurrence, evidence ->
        QueryCallbackBodyDocument.Anonymous(occurrence, evidence)
    }

internal fun QueryCallbackBodyWireDocument.toContract(): WireDocumentConversion<QueryCallbackBodyDocument> =
    when (this) {
        is QueryCallbackBodyWireDocument.Named ->
            callable.toContract().mapConverted { QueryCallbackBodyDocument.Named(it) }
        is QueryCallbackBodyWireDocument.Anonymous -> toContract().mapConverted { it }
    }

private fun QueryCallbackFlowWireDocument.toContract(): WireDocumentConversion<QueryCallbackFlowDocument> =
    when (this) {
        is QueryCallbackFlowWireDocument.Immutable ->
            flow.toContract().mapConverted { QueryCallbackFlowDocument.Immutable(it) }
        is QueryCallbackFlowWireDocument.Unavailable ->
            WireDocumentConversion.Converted(QueryCallbackFlowDocument.Unavailable(cause))
        is QueryCallbackFlowWireDocument.ContractRejected ->
            WireDocumentConversion.Converted(QueryCallbackFlowDocument.ContractRejected(cause))
        is QueryCallbackFlowWireDocument.Observed ->
            body.toContract().flatMapConverted { body ->
                binding.toContract().flatMapConverted { binding ->
                    invocations
                        .convertEach { it.toContract() }
                        .flatMapConverted { calls ->
                            ownerBindings
                                .convertEach { it.toContract() }
                                .flatMapConverted { owners ->
                                    combineConverted(
                                            BoundedProtocolList.create(calls).toWireDocumentConversion(),
                                            BoundedProtocolList.create(obligations).toWireDocumentConversion(),
                                            BoundedProtocolList.create(owners).toWireDocumentConversion(),
                                        ) { calls, obligations, owners ->
                                            forwarding.toContract().mapConverted { forwarding ->
                                                QueryCallbackFlowDocument.Observed(
                                                    basis,
                                                    body,
                                                    binding,
                                                    calls,
                                                    obligations,
                                                    owners,
                                                    scan,
                                                    forwarding,
                                                )
                                            }
                                        }
                                        .flattenConverted()
                                }
                        }
                }
            }
    }

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
                    combineConverted(policy, flow.toContract()) { policy, flow ->
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

internal fun QueryCallbackInvocationWireDocument.toContract(): WireDocumentConversion<QueryCallbackInvocationDocument> =
    combineConverted(occurrence.toContract(), owner.toContract()) { occurrence, owner ->
            forwardings
                .convertEach { it.toContract() }
                .flatMapConverted { forwardings ->
                    combineConverted(
                        BoundedProtocolList.create(callableTransfers).toWireDocumentConversion(),
                        BoundedProtocolList.create(forwardings).toWireDocumentConversion(),
                    ) { transfers, forwardings ->
                        QueryCallbackInvocationDocument(occurrence, owner, transfers, forwardings)
                    }
                }
        }
        .flattenConverted()
