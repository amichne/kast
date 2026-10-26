@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactCallbackInputFailureDocument {
    @Serializable
    @SerialName("CALLBACK")
    data class Callback(val cause: QueryCallbackFlowCauseDocument) : ImpactCallbackInputFailureDocument

    @Serializable
    @SerialName("CONTRACT")
    data class Contract(val cause: QueryCallbackFlowFailureDocument) : ImpactCallbackInputFailureDocument

    @Serializable
    @SerialName("GRAPH")
    data class Graph(val cause: QueryCallbackGraphCauseDocument) : ImpactCallbackInputFailureDocument

    @Serializable
    @SerialName("IMMUTABLE_GRAPH")
    data class ImmutableGraph(val cause: QueryCallbackGraphCauseDocument) : ImpactCallbackInputFailureDocument
}
