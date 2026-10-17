@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackResultProjectionDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackResultSiteDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackResultSiteWireDocument {
    @Serializable
    @SerialName("OCCURRENCE")
    data class Occurrence(val occurrence: RelationOccurrenceWireDocument) : QueryCallbackResultSiteWireDocument

    @Serializable
    @SerialName("INVOCATION")
    data class Invocation(val invocation: QueryCallbackInvocationWireDocument) : QueryCallbackResultSiteWireDocument

    @Serializable
    @SerialName("DIRECT_INVOCATION")
    data class DirectInvocation(val binding: QueryCallbackBindingWireDocument.Direct) :
        QueryCallbackResultSiteWireDocument

    @Serializable
    @SerialName("TARGET")
    data class Target(val target: QueryCallbackCallableWireDocument) : QueryCallbackResultSiteWireDocument

    @Serializable
    @SerialName("ANONYMOUS_TARGET")
    data class AnonymousTarget(val body: QueryCallbackBodyWireDocument.Anonymous) : QueryCallbackResultSiteWireDocument
}

@Serializable
@JsonClassDiscriminator("type")
internal sealed interface QueryCallbackResultProjectionWireDocument {
    @Serializable
    @SerialName("ADMITTED")
    data class Admitted(
        val subject: QueryReferenceWireDocument.ExactSymbol,
        val basis: ImpactSemanticBasisDocument,
        val domain: QueryRelationDomainFingerprint,
        val relation: RelationKindDocument,
        val site: QueryCallbackResultSiteWireDocument,
    ) : QueryCallbackResultProjectionWireDocument

    @Serializable
    @SerialName("UNPROVEN")
    data class Unproven(val cause: QueryCallbackFlowCauseDocument) : QueryCallbackResultProjectionWireDocument
}

internal fun QueryCallbackResultProjectionDocument.resultProjectionWire(): QueryCallbackResultProjectionWireDocument =
    when (this) {
        is QueryCallbackResultProjectionDocument.Admitted ->
            QueryCallbackResultProjectionWireDocument.Admitted(
                QueryReferenceWireDocument.ExactSymbol(subject.token.value),
                basis,
                domain,
                relation,
                site.resultSiteWire(),
            )
        is QueryCallbackResultProjectionDocument.Unproven -> QueryCallbackResultProjectionWireDocument.Unproven(cause)
    }

private fun QueryCallbackResultSiteDocument.resultSiteWire(): QueryCallbackResultSiteWireDocument =
    when (this) {
        is QueryCallbackResultSiteDocument.Occurrence ->
            QueryCallbackResultSiteWireDocument.Occurrence(occurrence.callbackWire())
        is QueryCallbackResultSiteDocument.Invocation ->
            QueryCallbackResultSiteWireDocument.Invocation(invocation.invocationWire())
        is QueryCallbackResultSiteDocument.DirectInvocation ->
            QueryCallbackResultSiteWireDocument.DirectInvocation(
                QueryCallbackBindingWireDocument.Direct(
                    binding.basis,
                    binding.occurrence.callbackWire(),
                    binding.owner.callbackWire(),
                )
            )
        is QueryCallbackResultSiteDocument.Target -> QueryCallbackResultSiteWireDocument.Target(target.callbackWire())
        is QueryCallbackResultSiteDocument.AnonymousTarget ->
            QueryCallbackResultSiteWireDocument.AnonymousTarget(body.callbackWire())
    }

internal fun QueryCallbackResultProjectionWireDocument.toContract():
    WireDocumentConversion<QueryCallbackResultProjectionDocument> =
    when (this) {
        is QueryCallbackResultProjectionWireDocument.Unproven ->
            WireDocumentConversion.Converted(QueryCallbackResultProjectionDocument.Unproven(cause))
        is QueryCallbackResultProjectionWireDocument.Admitted ->
            combineConverted(ProtocolText.parse(subject.token).toWireDocumentConversion(), site.toContract()) {
                token,
                site ->
                QueryCallbackResultProjectionDocument.Admitted(
                    QueryReferenceDocument.ExactSymbol(token),
                    basis,
                    domain,
                    relation,
                    site,
                )
            }
    }

private fun QueryCallbackResultSiteWireDocument.toContract(): WireDocumentConversion<QueryCallbackResultSiteDocument> =
    when (this) {
        is QueryCallbackResultSiteWireDocument.Occurrence ->
            occurrence.toContract().mapConverted(QueryCallbackResultSiteDocument::Occurrence)
        is QueryCallbackResultSiteWireDocument.Invocation ->
            invocation.toContract().mapConverted(QueryCallbackResultSiteDocument::Invocation)
        is QueryCallbackResultSiteWireDocument.DirectInvocation ->
            binding.toContract().flatMapConverted { binding ->
                if (binding is QueryCallbackBindingDocument.Direct)
                    WireDocumentConversion.Converted(QueryCallbackResultSiteDocument.DirectInvocation(binding))
                else WireDocumentConversion.Rejected
            }
        is QueryCallbackResultSiteWireDocument.Target ->
            target.toContract().mapConverted(QueryCallbackResultSiteDocument::Target)
        is QueryCallbackResultSiteWireDocument.AnonymousTarget ->
            body.toContract().mapConverted(QueryCallbackResultSiteDocument::AnonymousTarget)
    }
