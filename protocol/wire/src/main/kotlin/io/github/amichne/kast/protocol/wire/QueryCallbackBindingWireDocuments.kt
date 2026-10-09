@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyContractProvenanceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyInvocationKindDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackForwardingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackParameterIdentityDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal data class QueryCallbackParameterIdentityWireDocument(
    val callable: QueryCallbackCallableWireDocument,
    @ProtocolIntegerConstraint(minimum = 0) val position: Int,
    val parameter: RelationOccurrenceWireDocument,
)

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackBindingWireDocument {
    @Serializable
    @SerialName("BOUND")
    data class Bound(
        val invocation: ImpactInvocationReferenceDocument,
        @SerialName("invocation_occurrence") val invocationOccurrence: RelationOccurrenceWireDocument,
        @SerialName("invocation_owner") val invocationOwner: QueryCallbackBodyWireDocument,
        val callable: QueryCallbackCallableWireDocument,
        @ProtocolIntegerConstraint(minimum = 0) val position: Int,
        val parameter: RelationOccurrenceWireDocument,
    ) : QueryCallbackBindingWireDocument

    @Serializable
    @SerialName("DEFAULT")
    data class Default(
        val parameter: QueryCallbackParameterIdentityWireDocument,
        @SerialName("default_value") val defaultValue: RelationOccurrenceWireDocument,
    ) : QueryCallbackBindingWireDocument

    @Serializable
    @SerialName("DIRECT")
    data class Direct(
        val basis: ImpactSemanticBasisDocument,
        val occurrence: RelationOccurrenceWireDocument,
        val owner: QueryCallbackBodyWireDocument,
    ) : QueryCallbackBindingWireDocument

    @Serializable
    @SerialName("DEPENDENCY_CONTRACT")
    data class DependencyContract(
        val basis: ImpactSemanticBasisDocument,
        val occurrence: RelationOccurrenceWireDocument,
        val owner: QueryCallbackBodyWireDocument,
        val target: QueryExcludedCompilerTargetWireDocument,
        @ProtocolIntegerConstraint(minimum = 0) val position: Int,
        @SerialName("class_digest") val classDigest: String,
        val provenance: QueryCallbackDependencyContractProvenanceDocument,
        @SerialName("invocation_kind") val invocationKind: QueryCallbackDependencyInvocationKindDocument,
    ) : QueryCallbackBindingWireDocument

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: QueryCallbackFlowCauseDocument) : QueryCallbackBindingWireDocument
}

@Serializable
internal data class QueryCallbackForwardingWireDocument(
    val source: QueryCallbackParameterIdentityWireDocument,
    val argument: RelationOccurrenceWireDocument,
    val target: QueryCallbackBindingWireDocument,
    @SerialName("callable_transfers") val callableTransfers: List<ImpactCompilerTransferDocument>,
)

internal fun QueryCallbackBindingDocument.callbackWire(): QueryCallbackBindingWireDocument =
    when (this) {
        is QueryCallbackBindingDocument.Bound ->
            QueryCallbackBindingWireDocument.Bound(
                invocation,
                invocationOccurrence.callbackWire(),
                invocationOwner.callbackWire(),
                callable.callbackWire(),
                position.value,
                parameter.callbackWire(),
            )
        is QueryCallbackBindingDocument.Default ->
            QueryCallbackBindingWireDocument.Default(parameter.callbackWire(), defaultValue.callbackWire())
        is QueryCallbackBindingDocument.Direct ->
            QueryCallbackBindingWireDocument.Direct(basis, occurrence.callbackWire(), owner.callbackWire())
        is QueryCallbackBindingDocument.DependencyContract ->
            QueryCallbackBindingWireDocument.DependencyContract(
                basis,
                occurrence.callbackWire(),
                owner.callbackWire(),
                target.callbackWire(),
                position.value,
                classDigest.value,
                provenance,
                invocationKind,
            )
        is QueryCallbackBindingDocument.Unavailable -> QueryCallbackBindingWireDocument.Unavailable(cause)
    }

internal fun QueryCallbackParameterIdentityDocument.callbackWire() =
    QueryCallbackParameterIdentityWireDocument(callable.callbackWire(), position.value, parameter.callbackWire())

internal fun QueryCallbackParameterIdentityWireDocument.toContract():
    WireDocumentConversion<QueryCallbackParameterIdentityDocument> =
    combineConverted(
        callable.toContract(),
        ProtocolOffset.parse(position).toWireDocumentConversion(),
        parameter.toContract(),
    ) { callable, position, parameter ->
        QueryCallbackParameterIdentityDocument(callable, position, parameter)
    }

internal fun QueryCallbackForwardingDocument.callbackWire() =
    QueryCallbackForwardingWireDocument(
        source.callbackWire(),
        argument.callbackWire(),
        target.callbackWire(),
        callableTransfers.values,
    )

internal fun QueryCallbackBindingWireDocument.toContract(): WireDocumentConversion<QueryCallbackBindingDocument> =
    when (this) {
        is QueryCallbackBindingWireDocument.Unavailable ->
            WireDocumentConversion.Converted(QueryCallbackBindingDocument.Unavailable(cause))
        is QueryCallbackBindingWireDocument.Default ->
            combineConverted(parameter.toContract(), defaultValue.toContract()) { parameter, defaultValue ->
                QueryCallbackBindingDocument.Default(parameter, defaultValue)
            }
        is QueryCallbackBindingWireDocument.Direct ->
            combineConverted(occurrence.toContract(), owner.toContract()) { occurrence, owner ->
                QueryCallbackBindingDocument.Direct(basis, occurrence, owner)
            }
        is QueryCallbackBindingWireDocument.DependencyContract -> dependencyContractDocument()
        is QueryCallbackBindingWireDocument.Bound ->
            invocationOccurrence.toContract().flatMapConverted { callOccurrence ->
                invocationOwner.toContract().flatMapConverted { supplyingOwner ->
                    combineConverted(
                        callable.toContract(),
                        ProtocolOffset.parse(position).toWireDocumentConversion(),
                        parameter.toContract(),
                    ) { callable, position, parameter ->
                        QueryCallbackBindingDocument.Bound(
                            invocation,
                            callOccurrence,
                            supplyingOwner,
                            callable,
                            position,
                            parameter,
                        )
                    }
                }
            }
    }

internal fun QueryCallbackForwardingWireDocument.toContract(): WireDocumentConversion<QueryCallbackForwardingDocument> =
    source.toContract().flatMapConverted { source ->
        argument.toContract().flatMapConverted { argument ->
            target.toContract().flatMapConverted { target ->
                when (target) {
                    is QueryCallbackBindingDocument.Bound ->
                        BoundedProtocolList.create(callableTransfers).toWireDocumentConversion().mapConverted {
                            QueryCallbackForwardingDocument(source, argument, target, it)
                        }
                    is QueryCallbackBindingDocument.DependencyContract,
                    is QueryCallbackBindingDocument.Unavailable,
                    is QueryCallbackBindingDocument.Default,
                    is QueryCallbackBindingDocument.Direct -> WireDocumentConversion.Rejected
                }
            }
        }
    }

private fun QueryCallbackBindingWireDocument.DependencyContract.dependencyContractDocument():
    WireDocumentConversion<QueryCallbackBindingDocument> =
    combineConverted(occurrence.toContract(), owner.toContract(), target.toContract()) { occurrence, owner, target ->
            Triple(occurrence, owner, target)
        }
        .flatMapConverted { (occurrence, owner, target) ->
            combineConverted(
                ProtocolOffset.parse(position).toWireDocumentConversion(),
                ProtocolText.parse(classDigest).toWireDocumentConversion(),
            ) { position, digest ->
                QueryCallbackBindingDocument.DependencyContract(
                    basis,
                    occurrence,
                    owner,
                    target,
                    position,
                    digest,
                    provenance,
                    invocationKind,
                )
            }
        }
