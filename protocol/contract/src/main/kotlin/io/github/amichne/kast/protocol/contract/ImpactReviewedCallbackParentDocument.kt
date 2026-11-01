@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedCallbackParentDocument {
    @Serializable @SerialName("DECLARATION") data object Declaration : ImpactReviewedCallbackParentDocument

    @Serializable
    @SerialName("DECLARED_CALLBACK")
    data class DeclaredCallback(val binding: ImpactDeclaredCallbackValueBindingDocument) :
        ImpactReviewedCallbackParentDocument
}

internal fun ImpactReviewedValueBindingDocument.admitsParent(): Boolean =
    when (val value = parent) {
        ImpactReviewedCallbackParentDocument.Declaration -> true
        is ImpactReviewedCallbackParentDocument.DeclaredCallback ->
            value.binding.admits(enclosing) &&
                value.binding.contract.basis == basis &&
                value.binding.body.range.containsExecutionRange(supplierRange) &&
                invocationEvidence == ImpactReviewedInvocationEvidenceDocument.Unestablished
    }

internal val ImpactReviewedValueBindingDocument.parentExecutionOwner: ImpactExecutionOwnerDocument
    get() =
        when (val value = parent) {
            ImpactReviewedCallbackParentDocument.Declaration -> ImpactExecutionOwnerDocument.Declaration
            is ImpactReviewedCallbackParentDocument.DeclaredCallback ->
                ImpactExecutionOwnerDocument.DeclaredCallback(value.binding)
        }
