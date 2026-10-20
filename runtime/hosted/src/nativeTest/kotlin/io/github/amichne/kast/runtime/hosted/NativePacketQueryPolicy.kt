package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument

internal fun packetQueryDomain() =
    QueryExpansionScopeDocument.Sources(
        sourceSets = packetQueryList(listOf(packetQueryText("main"))),
        directory = QueryDirectoryScopeDocument(packetQueryText("src"), QueryContainmentDocument.DESCENDANTS),
        sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
        generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
    )
