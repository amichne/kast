package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ValueSite

/** Producer syntax cannot replace retained exact invocation identity. Weaker structural fixtures remain explicit. */
sealed interface QueryImpactProducerEvidence {
    val site: ValueSite

    data class Invocation(val producer: QueryImpactProducer) : QueryImpactProducerEvidence {
        override val site: ValueSite
            get() = producer.site
    }

    data class SiteOnly(override val site: ValueSite) : QueryImpactProducerEvidence
}
