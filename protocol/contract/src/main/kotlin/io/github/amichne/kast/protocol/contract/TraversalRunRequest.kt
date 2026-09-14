package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface TraversalRunPositionDocument {
    @Serializable @SerialName("start") data object Start : TraversalRunPositionDocument

    @Serializable
    @SerialName("resume")
    data class Resume(val continuation: TraversalContinuationDocument) : TraversalRunPositionDocument
}

@Serializable
sealed interface TraversalStrategyDocument {
    @Serializable @SerialName("breadth_first") data object BreadthFirst : TraversalStrategyDocument

    @Serializable
    @SerialName("bounded_fan_out")
    data class BoundedFanOut(val maximumEdgesPerNode: ProtocolCount) : TraversalStrategyDocument
}

@Serializable
data class TraversalRunRequest(
    val exactSelector: ProtocolText,
    val relation: RelationKindDocument,
    val maximumDepth: ProtocolCount,
    val maximumResults: ProtocolCount,
    val position: TraversalRunPositionDocument = TraversalRunPositionDocument.Start,
    val strategy: TraversalStrategyDocument = TraversalStrategyDocument.BreadthFirst,
) : OperationRequest
