@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Expansion owns native source membership. Name, kind, and package presentation predicates cannot enter. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface QueryExpansionScopeDocument {
    @Serializable @SerialName("RETAINED_SEED") data object RetainedSeed : QueryExpansionScopeDocument

    @Serializable @SerialName("WORKSPACE") data object Workspace : QueryExpansionScopeDocument

    @Serializable
    @SerialName("SOURCE_DOMAIN")
    data class Sources(
        @SerialName("source_sets")
        @ProtocolCollectionConstraint(minimumItems = 1, uniqueItems = true)
        val sourceSets: BoundedProtocolList<ProtocolText>,
        val directory: QueryDirectoryScopeDocument?,
        @SerialName("source_policy") val sourcePolicy: QueryDiscoverySourcePolicyDocument,
        @SerialName("generated_sources") val generatedSources: QueryDiscoveryInclusionPolicyDocument,
    ) : QueryExpansionScopeDocument
}
