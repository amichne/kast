package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryDocument
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints

internal fun RelationSearchBoundary.impactRequestedBoundary(): ImpactProjected<ImpactRequestedBoundaryDocument> =
    when (this) {
        RelationSearchBoundary.RETAINED_SUBJECT -> Refinement.Refined(ImpactRequestedBoundaryDocument.RetainedSeed)
        RelationSearchBoundary.WORKSPACE_EXPANSION -> Refinement.Refined(ImpactRequestedBoundaryDocument.Workspace)
        is RelationSearchBoundary.Explicit -> {
            val domain =
                relationDomainDocument(
                    scope,
                    SymbolDiscoveryConstraints(directory = directory, packageName = null, sourceSets = sourceSets),
                )
            if (domain == null) Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
            else Refinement.Refined(ImpactRequestedBoundaryDocument.SourceDomain(domain))
        }
    }
