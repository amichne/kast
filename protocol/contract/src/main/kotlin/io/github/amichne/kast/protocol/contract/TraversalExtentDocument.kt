@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** The original reach question; grants may pause its evaluation but never shorten it. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface TraversalExtentDocument {
    @Serializable @SerialName("EXHAUSTIVE") data object Exhaustive : TraversalExtentDocument

    @Serializable
    @SerialName("THROUGH_DEPTH")
    data class ThroughDepth(@SerialName("maximum_depth") val maximumDepth: ProtocolCount) : TraversalExtentDocument

    fun defaultExpansion(): QueryExpansionScopeDocument =
        when (this) {
            Exhaustive -> QueryExpansionScopeDocument.Workspace
            is ThroughDepth -> QueryExpansionScopeDocument.RetainedSeed
        }

    fun permitsExpansion(depth: TraversalDepthDocument): Boolean =
        when (this) {
            Exhaustive -> true
            is ThroughDepth -> depth.value < maximumDepth.value
        }
}
