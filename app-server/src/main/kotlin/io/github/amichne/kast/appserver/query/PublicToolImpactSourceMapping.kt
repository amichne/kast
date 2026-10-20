package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument

internal fun PublicToolImpactSource.lowerImpactSource(): QueryFromDocument.Impact =
    QueryFromDocument.Impact(
        QueryImpactSourceDocument(
            seeds,
            declarations,
            domain.lowerExpansionScope(),
            flow,
            models,
            requestedSiteReferences(),
        )
    )

private fun PublicToolImpactSource.requestedSiteReferences() =
    when (val sites = BoundedProtocolList.create(requestedSites?.values.orEmpty().map { it.reference() })) {
        is Refinement.Refined -> sites.value
        is Refinement.Rejected -> error("Admitted requested-site count remains bounded during projection")
    }
