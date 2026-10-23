@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationNodeDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationStepDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackNavigationWireDocument {
    @Serializable @SerialName("NOT_COMPUTED") data object NotComputed : QueryCallbackNavigationWireDocument

    @Serializable
    @SerialName("PROVEN")
    data class Proven(
        @ProtocolCollectionConstraint(maximumItems = 1000) val steps: List<QueryCallbackNavigationStepWireDocument>
    ) : QueryCallbackNavigationWireDocument

    @Serializable
    @SerialName("UNPROVEN")
    data class Unproven(
        val cause: QueryCallbackGraphCauseDocument,
        @ProtocolCollectionConstraint(maximumItems = 1000) val prefix: List<QueryCallbackNavigationStepWireDocument>,
    ) : QueryCallbackNavigationWireDocument
}

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackNavigationNodeWireDocument {
    @Serializable
    @SerialName("CALLABLE")
    data class Callable(val body: QueryCallbackBodyWireDocument) : QueryCallbackNavigationNodeWireDocument

    @Serializable
    @SerialName("FORMAL")
    data class Formal(
        val parameter: QueryCallbackParameterIdentityWireDocument,
        val supplier: QueryCallbackBindingWireDocument,
    ) : QueryCallbackNavigationNodeWireDocument
}

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackNavigationEvidenceWireDocument {
    @Serializable
    @SerialName("SUPPLY")
    data class Supply(
        val binding: QueryCallbackBindingWireDocument,
        val body: QueryCallbackBodyWireDocument.Anonymous,
    ) : QueryCallbackNavigationEvidenceWireDocument

    @Serializable
    @SerialName("FORWARD")
    data class Forward(val forwarding: QueryCallbackForwardingWireDocument) :
        QueryCallbackNavigationEvidenceWireDocument

    @Serializable
    @SerialName("INVOKE")
    data class Invoke(val invocation: QueryCallbackInvocationWireDocument) : QueryCallbackNavigationEvidenceWireDocument

    @Serializable
    @SerialName("DIRECT_INVOKE")
    data class DirectInvoke(val binding: QueryCallbackBindingWireDocument) : QueryCallbackNavigationEvidenceWireDocument

    @Serializable
    @SerialName("FUNCTION_INVOCATION")
    data class FunctionInvocation(
        val occurrence: RelationOccurrenceWireDocument,
        val policy: QueryCallbackNamedPolicyWireDocument,
    ) : QueryCallbackNavigationEvidenceWireDocument
}

@Serializable
internal data class QueryCallbackNavigationStepWireDocument(
    @ProtocolIntegerConstraint(minimum = 1) val depth: Int,
    val source: QueryCallbackNavigationNodeWireDocument,
    val target: QueryCallbackNavigationNodeWireDocument,
    val evidence: QueryCallbackNavigationEvidenceWireDocument,
)

internal fun QueryCallbackNavigationDocument.navigationWire(): QueryCallbackNavigationWireDocument =
    when (this) {
        QueryCallbackNavigationDocument.NotComputed -> QueryCallbackNavigationWireDocument.NotComputed
        is QueryCallbackNavigationDocument.Proven ->
            QueryCallbackNavigationWireDocument.Proven(steps.values.map { it.navigationWire() })
        is QueryCallbackNavigationDocument.Unproven ->
            QueryCallbackNavigationWireDocument.Unproven(cause, prefix.values.map { it.navigationWire() })
    }

private fun QueryCallbackNavigationStepDocument.navigationWire() =
    QueryCallbackNavigationStepWireDocument(
        depth.value,
        source.navigationWire(),
        target.navigationWire(),
        evidence.navigationWire(),
    )

private fun QueryCallbackNavigationNodeDocument.navigationWire(): QueryCallbackNavigationNodeWireDocument =
    when (this) {
        is QueryCallbackNavigationNodeDocument.Callable ->
            QueryCallbackNavigationNodeWireDocument.Callable(body.callbackWire())
        is QueryCallbackNavigationNodeDocument.Formal ->
            QueryCallbackNavigationNodeWireDocument.Formal(parameter.callbackWire(), supplier.callbackWire())
    }

private fun QueryCallbackNavigationEvidenceDocument.navigationWire(): QueryCallbackNavigationEvidenceWireDocument =
    when (this) {
        is QueryCallbackNavigationEvidenceDocument.Supply ->
            QueryCallbackNavigationEvidenceWireDocument.Supply(binding.callbackWire(), body.callbackWire())
        is QueryCallbackNavigationEvidenceDocument.Forward ->
            QueryCallbackNavigationEvidenceWireDocument.Forward(forwarding.callbackWire())
        is QueryCallbackNavigationEvidenceDocument.Invoke ->
            QueryCallbackNavigationEvidenceWireDocument.Invoke(
                QueryCallbackInvocationWireDocument(
                    invocation.occurrence.callbackWire(),
                    invocation.owner.callbackWire(),
                    invocation.callableTransfers.values,
                    invocation.forwardings.values.map { it.callbackWire() },
                )
            )
        is QueryCallbackNavigationEvidenceDocument.DirectInvoke ->
            QueryCallbackNavigationEvidenceWireDocument.DirectInvoke(binding.callbackWire())
        is QueryCallbackNavigationEvidenceDocument.FunctionInvocation ->
            QueryCallbackNavigationEvidenceWireDocument.FunctionInvocation(
                occurrence.callbackWire(),
                policy.callbackWire(),
            )
    }

internal fun QueryCallbackNavigationWireDocument.toContract(): WireDocumentConversion<QueryCallbackNavigationDocument> =
    when (this) {
        QueryCallbackNavigationWireDocument.NotComputed ->
            WireDocumentConversion.Converted(QueryCallbackNavigationDocument.NotComputed)
        is QueryCallbackNavigationWireDocument.Proven ->
            steps.navigationContract().mapConverted { QueryCallbackNavigationDocument.Proven(it) }
        is QueryCallbackNavigationWireDocument.Unproven ->
            prefix.navigationContract().mapConverted { QueryCallbackNavigationDocument.Unproven(cause, it) }
    }

private fun List<QueryCallbackNavigationStepWireDocument>.navigationContract() =
    BoundedProtocolList.create(this).toWireDocumentConversion().flatMapConverted {
        it.values
            .convertEach { step -> step.toContract() }
            .flatMapConverted { steps -> BoundedProtocolList.create(steps).toWireDocumentConversion() }
    }

internal fun QueryCallbackNavigationStepWireDocument.toContract():
    WireDocumentConversion<QueryCallbackNavigationStepDocument> =
    if (depth < 1) WireDocumentConversion.Rejected
    else
        combineConverted(
            TraversalDepthDocument.parse(depth).toWireDocumentConversion(),
            source.toContract(),
            target.toContract(),
            evidence.toContract(),
        ) { depth, source, target, evidence ->
            QueryCallbackNavigationStepDocument(depth, source, target, evidence)
        }

internal fun QueryCallbackNavigationNodeWireDocument.toContract():
    WireDocumentConversion<QueryCallbackNavigationNodeDocument> =
    when (this) {
        is QueryCallbackNavigationNodeWireDocument.Callable ->
            body.toContract().mapConverted { QueryCallbackNavigationNodeDocument.Callable(it) }
        is QueryCallbackNavigationNodeWireDocument.Formal ->
            combineConverted(parameter.toContract(), supplier.boundContract()) { parameter, supplier ->
                QueryCallbackNavigationNodeDocument.Formal(parameter, supplier)
            }
    }

private fun QueryCallbackBindingWireDocument.boundContract():
    WireDocumentConversion<QueryCallbackBindingDocument.Bound> =
    toContract().flatMapConverted {
        when (it) {
            is QueryCallbackBindingDocument.Bound -> WireDocumentConversion.Converted(it)
            else -> WireDocumentConversion.Rejected
        }
    }

internal fun QueryCallbackNavigationEvidenceWireDocument.toContract():
    WireDocumentConversion<QueryCallbackNavigationEvidenceDocument> =
    when (this) {
        is QueryCallbackNavigationEvidenceWireDocument.Supply ->
            combineConverted(binding.boundContract(), body.toContract()) { binding, body ->
                QueryCallbackNavigationEvidenceDocument.Supply(binding, body)
            }
        is QueryCallbackNavigationEvidenceWireDocument.Forward ->
            forwarding.toContract().mapConverted { QueryCallbackNavigationEvidenceDocument.Forward(it) }
        is QueryCallbackNavigationEvidenceWireDocument.Invoke ->
            invocation.toContract().mapConverted { QueryCallbackNavigationEvidenceDocument.Invoke(it) }
        is QueryCallbackNavigationEvidenceWireDocument.DirectInvoke ->
            binding.toContract().flatMapConverted {
                when (it) {
                    is QueryCallbackBindingDocument.Direct ->
                        WireDocumentConversion.Converted(QueryCallbackNavigationEvidenceDocument.DirectInvoke(it))
                    else -> WireDocumentConversion.Rejected
                }
            }
        is QueryCallbackNavigationEvidenceWireDocument.FunctionInvocation ->
            combineConverted(occurrence.toContract(), policy.toContract()) { occurrence, policy ->
                QueryCallbackNavigationEvidenceDocument.FunctionInvocation(occurrence, policy)
            }
    }

internal fun QueryCallbackNamedPolicyDocument.callbackWire(): QueryCallbackNamedPolicyWireDocument =
    when (this) {
        is QueryCallbackNamedPolicyDocument.Unavailable -> QueryCallbackNamedPolicyWireDocument.Unavailable(cause)
        QueryCallbackNamedPolicyDocument.AdmittedInline -> QueryCallbackNamedPolicyWireDocument.AdmittedInline
        QueryCallbackNamedPolicyDocument.AdmittedDirect -> QueryCallbackNamedPolicyWireDocument.AdmittedDirect
        is QueryCallbackNamedPolicyDocument.Excluded ->
            QueryCallbackNamedPolicyWireDocument.Excluded(reason, excludedBoundary.callbackWire())
    }

internal fun QueryCallbackNamedPolicyWireDocument.toContract():
    WireDocumentConversion<QueryCallbackNamedPolicyDocument> =
    when (this) {
        is QueryCallbackNamedPolicyWireDocument.Unavailable ->
            WireDocumentConversion.Converted(QueryCallbackNamedPolicyDocument.Unavailable(cause))
        QueryCallbackNamedPolicyWireDocument.AdmittedInline ->
            WireDocumentConversion.Converted(QueryCallbackNamedPolicyDocument.AdmittedInline)
        QueryCallbackNamedPolicyWireDocument.AdmittedDirect ->
            WireDocumentConversion.Converted(QueryCallbackNamedPolicyDocument.AdmittedDirect)
        is QueryCallbackNamedPolicyWireDocument.Excluded ->
            excludedBoundary.toContract().mapConverted { QueryCallbackNamedPolicyDocument.Excluded(reason, it) }
    }
