package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Surviving query walk exploration strategy. */
@Serializable
sealed interface TraversalStrategyDocument {
    @Serializable @SerialName("breadth_first") data object BreadthFirst : TraversalStrategyDocument

    @Serializable
    @SerialName("bounded_fan_out")
    data class BoundedFanOut(val maximumEdgesPerNode: ProtocolCount) : TraversalStrategyDocument
}
