package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.EmptyImpactRequestedSites
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument

internal fun PublicToolImpactSource.lowerImpactSource(): QueryFromDocument.Impact =
    QueryFromDocument.Impact(
        QueryImpactSourceDocument(
            seeds,
            declarations,
            domain.lowerExpansionScope(),
            flow,
            models.map { it.reference() },
            requestedSiteReferences(),
        )
    )

private fun PublicToolImpactSource.requestedSiteReferences() =
    requestedSites?.map { it.reference() } ?: EmptyImpactRequestedSites
