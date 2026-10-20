@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Public syntax selects native work without supplying retained value-state evidence. */
@Serializable
data class ImpactRequestedValueSiteDocument(
    val enclosing: ImpactDeclarationReferenceDocument,
    val range: ImpactSourceRangeDocument,
    val role: ImpactRequestedRoleDocument,
) {
    fun reference(): ImpactValueSiteReferenceDocument =
        ImpactValueSiteReferenceDocument(enclosing, range, role.reference())
}

@Serializable
data class ImpactRequestedInvocationDocument(
    val range: ImpactSourceRangeDocument,
    val callable: ImpactDeclarationReferenceDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactRequestedRoleDocument {
    @Serializable @SerialName("EXPRESSION_RESULT") data object ExpressionResult : ImpactRequestedRoleDocument

    @Serializable @SerialName("LOCAL_BINDING") data object LocalBinding : ImpactRequestedRoleDocument

    @Serializable @SerialName("LOCAL_READ") data object LocalRead : ImpactRequestedRoleDocument

    @Serializable @SerialName("RETURN") data object Return : ImpactRequestedRoleDocument

    @Serializable @SerialName("PROPERTY_ASSIGNMENT") data object PropertyAssignment : ImpactRequestedRoleDocument

    @Serializable
    @SerialName("ARGUMENT")
    data class Argument(val invocation: ImpactRequestedInvocationDocument, val index: ProtocolOffset) :
        ImpactRequestedRoleDocument
}

private fun ImpactRequestedRoleDocument.reference(): ImpactValueRoleDocument =
    when (this) {
        ImpactRequestedRoleDocument.ExpressionResult -> ImpactValueRoleDocument.ExpressionResult
        ImpactRequestedRoleDocument.LocalBinding -> ImpactValueRoleDocument.LocalBinding
        ImpactRequestedRoleDocument.LocalRead -> ImpactValueRoleDocument.LocalRead
        ImpactRequestedRoleDocument.Return -> ImpactValueRoleDocument.Return
        ImpactRequestedRoleDocument.PropertyAssignment -> ImpactValueRoleDocument.PropertyAssignment
        is ImpactRequestedRoleDocument.Argument ->
            ImpactValueRoleDocument.Argument(
                ImpactInvocationReferenceDocument(invocation.range, invocation.callable),
                index,
            )
    }
