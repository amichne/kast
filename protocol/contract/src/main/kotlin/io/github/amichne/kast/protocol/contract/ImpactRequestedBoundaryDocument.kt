@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Original requested boundary, preserving every explicit restriction even when a read rejected. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactRequestedBoundaryDocument {
    @Serializable @SerialName("RETAINED_SEED") data object RetainedSeed : ImpactRequestedBoundaryDocument

    @Serializable @SerialName("WORKSPACE") data object Workspace : ImpactRequestedBoundaryDocument

    @Serializable
    @SerialName("SOURCE_DOMAIN")
    data class SourceDomain(val domain: QueryRelationDomainDocument) : ImpactRequestedBoundaryDocument
}
