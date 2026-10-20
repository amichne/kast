package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument

/** Only the explicit source domain changes. Existing grants, retention, exclusions, and scheduling remain the same. */
internal enum class NativePacketCompositeQueryPolicy {
    PRODUCTION,
    COMBINED;

    val sourcePolicy
        get() =
            when (this) {
                PRODUCTION -> QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY
                COMBINED -> QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST
            }

    val sourceSets
        get() =
            when (this) {
                PRODUCTION -> listOf(packetQueryText("main"))
                COMBINED -> listOf(packetQueryText("main"), packetQueryText("test"))
            }

    val domain
        get() =
            QueryExpansionScopeDocument.Sources(
                sourceSets = packetQueryList(sourceSets),
                directory = QueryDirectoryScopeDocument(packetQueryText("src"), QueryContainmentDocument.DESCENDANTS),
                sourcePolicy = sourcePolicy,
                generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
            )
}
