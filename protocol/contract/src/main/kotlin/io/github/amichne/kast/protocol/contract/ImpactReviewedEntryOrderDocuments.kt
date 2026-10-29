@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedEntryReadOrderDocument {
    @Serializable @SerialName("DIRECT") data object Direct : ImpactReviewedEntryReadOrderDocument

    @Serializable
    @SerialName("DESTRUCTURED")
    data class Destructured(
        val declarationRange: ImpactSourceRangeDocument,
        val prefix: ImpactReviewedEntryReadPrefixDocument,
    ) : ImpactReviewedEntryReadOrderDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReviewedEntryReadPrefixDocument {
    @Serializable @SerialName("EMPTY") data object Empty : ImpactReviewedEntryReadPrefixDocument

    @Serializable
    @SerialName("FIRST_COMPONENT")
    data class FirstComponent(val invocation: ImpactReviewedEntryComponentInvocationDocument) :
        ImpactReviewedEntryReadPrefixDocument
}

@Serializable
data class ImpactReviewedEntryComponentInvocationDocument(
    val invocation: ImpactInvocationReferenceDocument,
    val selected: ImpactReviewedOrdinaryTargetDocument.Function,
    val artifacts: ImpactReviewedMapArtifactsDocument,
)
