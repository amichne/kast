package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ValueSite

/** Native occurrence selection accepts each contribution while exact selection preserves the complete value state. */
sealed interface QueryImpactSiteSelection {
    val site: ValueSite

    fun matches(candidate: ValueSite): Boolean

    class Occurrence private constructor(override val site: ValueSite) : QueryImpactSiteSelection {
        override fun matches(candidate: ValueSite): Boolean =
            site.identity.owner == candidate.identity.owner &&
                site.basis == candidate.basis &&
                site.identity.execution == candidate.identity.execution &&
                site.range == candidate.range &&
                site.role == candidate.role

        companion object {
            internal fun fromNative(site: ValueSite): QueryImpactSiteSelection =
                if (
                    site.valuePath.segments.isEmpty() &&
                        site.callStack.frames.isEmpty() &&
                        site.predicates.entries.isEmpty()
                )
                    Occurrence(site)
                else ExactValue.fromNative(site)
        }
    }

    class ExactValue private constructor(override val site: ValueSite) : QueryImpactSiteSelection {
        override fun matches(candidate: ValueSite): Boolean = site == candidate

        companion object {
            internal fun fromNative(site: ValueSite): ExactValue = ExactValue(site)
        }
    }

    companion object {
        internal fun fromNative(site: ValueSite): QueryImpactSiteSelection = Occurrence.fromNative(site)
    }
}
