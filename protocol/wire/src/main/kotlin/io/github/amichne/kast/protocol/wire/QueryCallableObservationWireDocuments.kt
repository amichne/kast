@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableDispositionDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableModuleKindDocument
import io.github.amichne.kast.protocol.contract.QuerySourceLessCallableOriginDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallableObservationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data class QuerySourceLessCallableWireDocument(
    @SerialName("compiler_evidence") val compilerEvidence: CompilerSymbolEvidenceWireDocument,
    val kind: SymbolKindWireDocument,
    val origin: QuerySourceLessCallableOriginDocument,
    @SerialName("module_kind") val moduleKind: QuerySourceLessCallableModuleKindDocument,
    @SerialName("module_name")
    @ProtocolStringConstraint(minimumLength = 1, maximumLength = 512, pattern = "^[^\\x00-\\x1F\\x7F-\\x9F]+$")
    val moduleName: String,
)

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallableTargetWireDocument {
    @Serializable
    @SerialName("DIRECT_INVOCATIONS")
    data class DirectInvocations(
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 1000)
        val invocations: List<QueryImmutableCallbackUseWireDocument.Direct>
    ) : QueryCallableTargetWireDocument

    @Serializable
    @SerialName("UNAVAILABLE_SUPPLY")
    data class UnavailableSupply(
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 1000)
        val causes: List<io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument>
    ) : QueryCallableTargetWireDocument

    @Serializable
    @SerialName("CALLBACK_SUPPLIES")
    data class CallbackSupplies(
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 1000)
        val supplies: List<QueryImmutableCallbackUseWireDocument.Supplied>,
        @io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 1000)
        val formals: List<QueryCallbackParameterIdentityWireDocument>,
    ) : QueryCallableTargetWireDocument

    @Serializable
    @SerialName("UNAVAILABLE_REFERENCE")
    data class UnavailableReference(
        val cause: io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
    ) : QueryCallableTargetWireDocument

    @Serializable
    @SerialName("NAMED_REFERENCE")
    data class NamedReference(val reference: QueryNamedCallbackReferenceWireDocument) : QueryCallableTargetWireDocument

    @Serializable
    @SerialName("PARAMETER_INVOCATION")
    data class ParameterInvocation(
        val parameter: QueryCallbackParameterIdentityWireDocument,
        val suppliers: QueryCallbackSupplierInventoryWireDocument,
        val invocation: QueryCallbackInvocationWireDocument,
    ) : QueryCallableTargetWireDocument

    @Serializable
    @SerialName("SOURCE_LESS")
    data class SourceLess(
        val callable: QuerySourceLessCallableWireDocument,
        val disposition: QuerySourceLessCallableDispositionDocument,
    ) : QueryCallableTargetWireDocument
}

@Serializable
internal data class QueryCallableObservationWireDocument(
    val occurrence: RelationOccurrenceWireDocument,
    @SerialName("lexical_owner") val lexicalOwner: QueryCallbackCallableWireDocument,
    val body: QueryCallbackBodyWireDocument,
    val target: QueryCallableTargetWireDocument,
)

internal fun QueryCallableObservationDocument.toWireDocument() =
    QueryCallableObservationWireDocument(
        RelationOccurrenceWireDocument(
            occurrence.candidateSelector.value,
            occurrence.file.value,
            occurrence.range.toWireDocument(),
        ),
        lexicalOwner.callbackWire(),
        body.callbackWire(),
        when (val value = target) {
            is QueryCallableTargetDocument.DirectInvocations ->
                QueryCallableTargetWireDocument.DirectInvocations(
                    value.invocations.values.map {
                        QueryImmutableCallbackUseWireDocument.Direct(
                            it.value.immutableCallbackWire(),
                            it.binding.callbackWire(),
                        )
                    }
                )
            is QueryCallableTargetDocument.UnavailableSupply ->
                QueryCallableTargetWireDocument.UnavailableSupply(value.obligations.values)
            is QueryCallableTargetDocument.CallbackSupplies ->
                QueryCallableTargetWireDocument.CallbackSupplies(
                    value.supplies.values.map { it.suppliedWire() },
                    value.formals.values.map { it.callbackWire() },
                )
            is QueryCallableTargetDocument.UnavailableReference ->
                QueryCallableTargetWireDocument.UnavailableReference(value.cause)
            is QueryCallableTargetDocument.NamedReference ->
                QueryCallableTargetWireDocument.NamedReference(value.reference.callbackWire())
            is QueryCallableTargetDocument.ParameterInvocation ->
                QueryCallableTargetWireDocument.ParameterInvocation(
                    value.parameter.callbackWire(),
                    value.suppliers.supplierInventoryWire(),
                    value.invocation.invocationWire(),
                )
            is QueryCallableTargetDocument.SourceLess ->
                QueryCallableTargetWireDocument.SourceLess(
                    QuerySourceLessCallableWireDocument(
                        value.callable.compilerEvidence.toWireDocument(),
                        value.callable.kind.toWireDocument(),
                        value.callable.origin,
                        value.callable.moduleKind,
                        value.callable.moduleName.value,
                    ),
                    value.disposition,
                )
        },
    )

internal fun QueryCallableObservationWireDocument.toContract():
    WireDocumentConversion<QueryCallableObservationDocument> =
    combineConverted(occurrence.toContract(), lexicalOwner.toContract(), body.toContract(), target.toContract()) {
            occurrence,
            lexicalOwner,
            body,
            target ->
            QueryCallableObservationDocument.create(occurrence, lexicalOwner, body, target).toWireDocumentConversion()
        }
        .flattenConverted()

private fun QueryCallableTargetWireDocument.toContract(): WireDocumentConversion<QueryCallableTargetDocument> =
    when (this) {
        is QueryCallableTargetWireDocument.DirectInvocations ->
            invocations
                .convertEach { direct ->
                    direct.toContract().flatMapConverted {
                        if (it is io.github.amichne.kast.protocol.contract.QueryImmutableCallbackUseDocument.Direct)
                            WireDocumentConversion.Converted(it)
                        else WireDocumentConversion.Rejected
                    }
                }
                .flatMapConverted {
                    io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(it).toWireDocumentConversion()
                }
                .mapConverted { QueryCallableTargetDocument.DirectInvocations(it) }
        is QueryCallableTargetWireDocument.UnavailableSupply ->
            io.github.amichne.kast.protocol.contract.QueryCallbackGraphObligationsDocument.from(causes)
                .toWireDocumentConversion()
                .mapConverted { QueryCallableTargetDocument.UnavailableSupply(it) }
        is QueryCallableTargetWireDocument.CallbackSupplies -> suppliesContract()
        is QueryCallableTargetWireDocument.UnavailableReference ->
            WireDocumentConversion.Converted(QueryCallableTargetDocument.UnavailableReference(cause))
        is QueryCallableTargetWireDocument.NamedReference ->
            reference.toContract().mapConverted { QueryCallableTargetDocument.NamedReference(it) }
        is QueryCallableTargetWireDocument.ParameterInvocation ->
            combineConverted(parameter.toContract(), suppliers.toContract(), invocation.toContract()) {
                parameter,
                suppliers,
                invocation ->
                QueryCallableTargetDocument.ParameterInvocation(parameter, suppliers, invocation)
            }
        is QueryCallableTargetWireDocument.SourceLess ->
            combineConverted(
                    callable.compilerEvidence.toContract(),
                    ProtocolText.parse(callable.moduleName).toWireDocumentConversion(),
                ) { evidence, name ->
                    QuerySourceLessCallableDocument.create(
                            evidence,
                            callable.kind.toContract(),
                            callable.origin,
                            callable.moduleKind,
                            name,
                        )
                        .toWireDocumentConversion()
                }
                .flattenConverted()
                .mapConverted { QueryCallableTargetDocument.SourceLess(it, disposition) }
    }

@Serializable
internal data class QueryWalkCallableObservationWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    @ProtocolIntegerConstraint(minimum = 0) val depth: Int,
    val observation: QueryCallableObservationWireDocument,
    @SerialName("requested_domain") val requestedDomain: QueryRelationRequestedDomainDocument,
    @SerialName("effective_domain") val effectiveDomain: QueryRelationDomainDocument,
    @SerialName("domain_fingerprint") val domainFingerprint: QueryRelationDomainFingerprint,
)

internal fun QueryWalkCallableObservationDocument.toWireDocument() =
    QueryWalkCallableObservationWireDocument(
        QueryReferenceWireDocument.ExactSymbol(subject.token.value),
        depth.value,
        observation.toWireDocument(),
        requestedDomain,
        effectiveDomain,
        domainFingerprint,
    )

internal fun QueryWalkCallableObservationWireDocument.toContract():
    WireDocumentConversion<QueryWalkCallableObservationDocument> =
    combineConverted(
            ProtocolText.parse(subject.token).toWireDocumentConversion(),
            TraversalDepthDocument.parse(depth).toWireDocumentConversion(),
            observation.toContract(),
        ) { token, depth, observation ->
            QueryWalkCallableObservationDocument(
                QueryReferenceDocument.ExactSymbol(token),
                depth,
                observation,
                requestedDomain,
                effectiveDomain,
                domainFingerprint,
            )
        }
        .flatMapConverted { value ->
            if (value.observation.admitsDomain(effectiveDomain, domainFingerprint))
                WireDocumentConversion.Converted(value)
            else WireDocumentConversion.Rejected
        }

internal fun io.github.amichne.kast.protocol.contract.QueryCallbackInvocationDocument.invocationWire() =
    QueryCallbackInvocationWireDocument(
        occurrence.callbackWire(),
        owner.callbackWire(),
        callableTransfers.values,
        forwardings.values.map { it.callbackWire() },
    )

private fun QueryCallableTargetWireDocument.CallbackSupplies.suppliesContract():
    WireDocumentConversion<QueryCallableTargetDocument> =
    supplies
        .convertEach { supply ->
            supply.toContract().flatMapConverted {
                if (it is io.github.amichne.kast.protocol.contract.QueryImmutableCallbackUseDocument.Supplied)
                    WireDocumentConversion.Converted(it)
                else WireDocumentConversion.Rejected
            }
        }
        .flatMapConverted {
            io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(it).toWireDocumentConversion()
        }
        .flatMapConverted { supplies ->
            formals
                .convertEach { it.toContract() }
                .flatMapConverted {
                    io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(it).toWireDocumentConversion()
                }
                .mapConverted { QueryCallableTargetDocument.CallbackSupplies(supplies, it) }
        }
