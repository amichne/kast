@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class QueryCallbackCallableWireDocument(
    val declaration: RelationOccurrenceWireDocument,
    @SerialName("compiler_target") val compilerTarget: QueryExcludedCompilerTargetWireDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCallbackBodyWireDocument {
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
data class QueryCallbackInvocationWireDocument(
    val occurrence: RelationOccurrenceWireDocument,
    val owner: QueryCallbackBodyWireDocument,
    @SerialName("callable_transfers") val callableTransfers: List<ImpactCompilerTransferDocument>,
    @ProtocolCollectionConstraint(maximumItems = 1000) val forwardings: List<QueryCallbackForwardingWireDocument>,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryCallbackFlowWireDocument {
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

fun RelationOccurrenceDocument.callbackWire() =
    RelationOccurrenceWireDocument(candidateSelector.value, file.value, range.toWireDocument())

fun QueryCallbackCallableDocument.callbackWire() =
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

fun QueryCallbackBodyDocument.Anonymous.callbackWire() =
    QueryCallbackBodyWireDocument.Anonymous(occurrence.callbackWire(), compilerEvidence.toWireDocument())

fun QueryCallbackBodyDocument.callbackWire(): QueryCallbackBodyWireDocument =
    when (this) {
        is QueryCallbackBodyDocument.Named -> QueryCallbackBodyWireDocument.Named(callable.callbackWire())
        is QueryCallbackBodyDocument.Anonymous -> callbackWire()
    }

fun QueryCallbackFlowDocument.callbackWire(): QueryCallbackFlowWireDocument =
    when (this) {
        is QueryCallbackFlowDocument.Immutable -> QueryCallbackFlowWireDocument.Immutable(flow.immutableFlowWire())
        is QueryCallbackFlowDocument.Observed -> observedCallbackWire()
        is QueryCallbackFlowDocument.Unavailable -> QueryCallbackFlowWireDocument.Unavailable(cause)
        is QueryCallbackFlowDocument.ContractRejected -> QueryCallbackFlowWireDocument.ContractRejected(cause)
    }

fun QueryCallbackFlowDocument.Observed.observedCallbackWire(): QueryCallbackFlowWireDocument.Observed =
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

fun QueryCallbackCallableWireDocument.toContract(): WireDocumentConversion<QueryCallbackCallableDocument> =
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

fun QueryCallbackBodyWireDocument.Anonymous.toContract(): WireDocumentConversion<QueryCallbackBodyDocument.Anonymous> =
    combineConverted(occurrence.toContract(), compilerEvidence.toContract()) { occurrence, evidence ->
        QueryCallbackBodyDocument.Anonymous(occurrence, evidence)
    }

fun QueryCallbackBodyWireDocument.toContract(): WireDocumentConversion<QueryCallbackBodyDocument> =
    when (this) {
        is QueryCallbackBodyWireDocument.Named ->
            callable.toContract().mapConverted { QueryCallbackBodyDocument.Named(it) }
        is QueryCallbackBodyWireDocument.Anonymous -> toContract().mapConverted { it }
    }

fun QueryCallbackFlowWireDocument.toContract(): WireDocumentConversion<QueryCallbackFlowDocument> =
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

fun QueryCallbackInvocationWireDocument.toContract(): WireDocumentConversion<QueryCallbackInvocationDocument> =
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
