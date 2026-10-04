package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument

/** Public domain syntax lowers to the existing expansion owner, independently of producer selection. */
internal fun PublicToolExpansionScope.lowerExpansionScope(): QueryExpansionScopeDocument =
    when (this) {
        PublicToolImpactWorkspaceDomain -> QueryExpansionScopeDocument.Workspace
        PublicToolImpactRetainedDomain -> QueryExpansionScopeDocument.RetainedSeed
        is PublicToolImpactSourceDomain ->
            QueryExpansionScopeDocument.Sources(
                sourceSets,
                directory?.let {
                    QueryDirectoryScopeDocument(
                        it,
                        if (includeSubdirectories ?: true) QueryContainmentDocument.DESCENDANTS
                        else QueryContainmentDocument.DIRECT,
                    )
                },
                when (sourcePolicy) {
                    PublicToolSourcePolicy.PRODUCTION_ONLY -> QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY
                    PublicToolSourcePolicy.TEST_ONLY -> QueryDiscoverySourcePolicyDocument.TEST_ONLY
                    PublicToolSourcePolicy.PRODUCTION_AND_TEST -> QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST
                },
                when (generatedSources) {
                    PublicToolGeneratedSources.EXCLUDE -> QueryDiscoveryInclusionPolicyDocument.EXCLUDE
                    PublicToolGeneratedSources.INCLUDE -> QueryDiscoveryInclusionPolicyDocument.INCLUDE
                },
            )
    }
