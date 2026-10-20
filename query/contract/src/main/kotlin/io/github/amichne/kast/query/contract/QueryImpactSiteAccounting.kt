package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import java.util.Collections

class QueryImpactSiteExclusion
internal constructor(
    val pathOrdinal: QueryRetainedRowOrdinal,
    val exclusion: QueryImpactScopeExclusion,
)

sealed interface QueryImpactSiteOutcome {
    class Reached
    private constructor(
        val pathOrdinals: List<QueryRetainedRowOrdinal>,
        val exclusions: List<QueryImpactSiteExclusion>,
    ) : QueryImpactSiteOutcome {
        companion object {
            internal fun fromOriginalPaths(
                selection: QueryImpactSiteSelection,
                paths: List<QueryImpactPath>,
                domain: RelationSearchBoundary,
            ): Refinement<QueryImpactSiteOutcome, QueryImpactLedgerFailure> = account(selection, paths, domain)

            private fun account(
                selection: QueryImpactSiteSelection,
                paths: List<QueryImpactPath>,
                domain: RelationSearchBoundary,
            ): Refinement<QueryImpactSiteOutcome, QueryImpactLedgerFailure> {
                val reached = mutableListOf<QueryRetainedRowOrdinal>()
                val exclusions = mutableListOf<QueryImpactSiteExclusion>()
                for ((index, path) in paths.withIndex()) {
                    val ordinal =
                        when (val admitted = QueryRetainedRowOrdinal.admit(index, paths.size)) {
                            is Refinement.Refined -> admitted.value
                            is Refinement.Rejected ->
                                return Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_TERMINAL)
                        }
                    val excluded = (path.terminal as? QueryImpactTerminal.ExplicitScopeExclusion)?.exclusion
                    if (excluded != null && selection.matches(excluded.site)) {
                        if (excluded.domain == domain) exclusions += QueryImpactSiteExclusion(ordinal, excluded)
                    } else if (path.reaches(selection)) reached += ordinal
                }
                return Refinement.Refined(
                    when {
                        reached.isNotEmpty() -> Reached(snapshot(reached), snapshot(exclusions))
                        exclusions.isNotEmpty() -> Excluded.fromOriginalExclusions(exclusions)
                        else -> RelationshipUnproven
                    }
                )
            }
        }
    }

    class Excluded private constructor(val exclusions: List<QueryImpactSiteExclusion>) : QueryImpactSiteOutcome {
        companion object {
            internal fun fromOriginalExclusions(exclusions: List<QueryImpactSiteExclusion>): QueryImpactSiteOutcome =
                if (exclusions.isEmpty()) RelationshipUnproven else Excluded(snapshot(exclusions))
        }
    }

    data object RelationshipUnproven : QueryImpactSiteOutcome
}

/** Each original target has exactly one outcome; path partitions preserve both arrivals and proven exits. */
class QueryImpactSiteAccounting
private constructor(
    val requestedSiteOrdinal: QueryImpactWitnessOrdinal,
    val requested: QueryImpactRequestedSite,
    val outcome: QueryImpactSiteOutcome,
) {
    companion object {
        internal fun fromRequestedSites(
            requestedSites: List<QueryImpactRequestedSite>,
            paths: List<QueryImpactPath>,
            domain: RelationSearchBoundary,
        ): Refinement<List<QueryImpactSiteAccounting>, QueryImpactLedgerFailure> {
            val result = mutableListOf<QueryImpactSiteAccounting>()
            for ((index, requested) in requestedSites.withIndex()) {
                val ordinal =
                    when (val admitted = QueryImpactWitnessOrdinal.parse(index)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_TERMINAL)
                    }
                val outcome =
                    when (
                        val admitted =
                            QueryImpactSiteOutcome.Reached.fromOriginalPaths(requested.selection, paths, domain)
                    ) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return admitted
                    }
                result += QueryImpactSiteAccounting(ordinal, requested, outcome)
            }
            return Refinement.Refined(snapshot(result))
        }
    }
}

private fun QueryImpactPath.reaches(selection: QueryImpactSiteSelection): Boolean =
    selection.matches(producer) ||
        selection.matches(destination) ||
        steps.any { selection.matches(it.source) || selection.matches(it.target) }

private fun <T> snapshot(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
