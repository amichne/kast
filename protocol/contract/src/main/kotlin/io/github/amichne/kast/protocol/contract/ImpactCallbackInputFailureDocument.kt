@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactCallbackInputFailureDocument {
    @Serializable
    @SerialName("IMPLEMENTATION_REJECTED")
    data class ImplementationRejected(val cause: ImpactRelationCompilerFailureDocument) :
        ImpactCallbackInputFailureDocument

    @Serializable
    @SerialName("IMPLEMENTATION_INCOMPLETE")
    data class ImplementationIncomplete(val dispatch: ImpactImplementationDispatchDocument) :
        ImpactCallbackInputFailureDocument

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

@Serializable
enum class ImpactRelationCompilerFailureDocument {
    WORKSPACE_ROOT_MISMATCH,
    GENERATION_MOVED,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    COMPILER_IDENTITY_UNAVAILABLE,
    CONTINUATION_CURSOR_MOVED,
    COMPILER_CONTRACT_VIOLATION,
}
