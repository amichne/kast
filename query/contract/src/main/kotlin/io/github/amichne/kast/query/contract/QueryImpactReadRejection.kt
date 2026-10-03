package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueSite

/** Actual rejected native reads retain the exact requested site, domain and closed failure without inventing a step. */
sealed interface QueryImpactReadRejection {
    val source: ValueSite
    val domain: RelationSearchBoundary
    val examinedWorkUnits: RelationWorkCount

    data class Native(
        override val source: ValueSite,
        override val domain: RelationSearchBoundary,
        val cause: ValueFlowRejection,
        override val examinedWorkUnits: RelationWorkCount,
    ) : QueryImpactReadRejection

    data class Contract(
        override val source: ValueSite,
        override val domain: RelationSearchBoundary,
        val cause: ValueFlowStepFailure,
        override val examinedWorkUnits: RelationWorkCount,
    ) : QueryImpactReadRejection
}
