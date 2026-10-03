@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** A query cutoff retains its positive route or capacity witness. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactExecutionStopDocument {
    @Serializable
    @SerialName("CYCLE")
    data class Cycle(
        val producer: ImpactValueSiteReferenceDocument,
        val prefix: BoundedProtocolList<ImpactPathStepDocument>,
        val repeatedAt: QueryDiscoveryCountDocument,
        val source: ImpactValueSiteReferenceDocument,
    ) : ImpactExecutionStopDocument

    @Serializable
    @SerialName("CHECKPOINT_CAPACITY")
    data class CheckpointCapacity(
        val source: ImpactValueSiteReferenceDocument,
        val requiredBytes: QueryDiscoveryCountDocument,
        val availableBytes: QueryDiscoveryCountDocument,
    ) : ImpactExecutionStopDocument
}
