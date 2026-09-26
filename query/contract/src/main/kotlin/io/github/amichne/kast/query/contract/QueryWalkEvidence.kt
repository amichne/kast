package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalExpansionRemainder
import io.github.amichne.kast.traversal.contract.TraversalFrontierCount
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPartialExpansion
import io.github.amichne.kast.traversal.contract.TraversalProgress
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.contract.TraversalStrategy

/** The traversal records producing this row. Combining operators may merge their exact facts. */
sealed interface QueryWalkArrival {
    data object None : QueryWalkArrival

    @ConsistentCopyVisibility
    data class Proven private constructor(val records: List<TraversalRecord>) : QueryWalkArrival {
        companion object {
            fun one(record: TraversalRecord): Proven = Proven(listOf(record))

            fun merge(first: Proven, second: Proven): Proven =
                Proven(java.util.Collections.unmodifiableList((first.records + second.records).distinct().sorted()))
        }
    }
}

fun QueryWalkArrival.merge(other: QueryWalkArrival): QueryWalkArrival =
    when {
        this is QueryWalkArrival.Proven && other is QueryWalkArrival.Proven ->
            QueryWalkArrival.Proven.merge(this, other)
        this is QueryWalkArrival.Proven -> this
        else -> other
    }

sealed interface QueryWalkCoverage {
    data object Complete : QueryWalkCoverage

    @ConsistentCopyVisibility
    data class Resumable
    internal constructor(
        val limitations: List<TraversalLimitation>,
        val relationLimitations: List<RelationLimitation>,
    ) : QueryWalkCoverage

    @ConsistentCopyVisibility
    data class TerminalIncomplete
    internal constructor(
        val limitations: List<TraversalLimitation>,
        val relationLimitations: List<RelationLimitation>,
    ) : QueryWalkCoverage

    companion object {
        internal fun from(qualification: TraversalQualification): QueryWalkCoverage {
            val limitations = qualification.limitations.immutableOrderedBy { it.ordinal }
            val relationLimitations = qualification.relationLimitations.immutableOrderedBy { it.ordinal }
            return when (qualification) {
                is TraversalQualification.Resumable -> Resumable(limitations, relationLimitations)
                is TraversalQualification.TerminalIncomplete -> TerminalIncomplete(limitations, relationLimitations)
            }
        }
    }
}

/** Immutable page-local evidence for a partially explored frontier entry. */
@ConsistentCopyVisibility
data class QueryWalkPartialExpansion
private constructor(
    val entry: TraversalFrontierEntry,
    val limitations: List<RelationLimitation>,
    val remainder: TraversalExpansionRemainder,
) {
    companion object {
        internal fun from(expansion: TraversalPartialExpansion): QueryWalkPartialExpansion =
            QueryWalkPartialExpansion(
                expansion.entry,
                expansion.limitations.immutableOrderedBy { it.ordinal },
                expansion.remainder,
            )
    }
}

/** Page-local traversal proof, excluding record payloads already retained on result rows. */
@ConsistentCopyVisibility
data class QueryWalkObservation
private constructor(
    val subject: SymbolSelector,
    val meaning: RelationMeaning,
    val budget: TraversalBudget,
    val strategy: TraversalStrategy,
    val progress: TraversalProgress,
    val expandedFrontier: TraversalFrontierCount,
    val partialExpansions: List<QueryWalkPartialExpansion>,
    val coverage: QueryWalkCoverage,
) {
    companion object {
        fun from(result: TraversalResult.Complete): QueryWalkObservation =
            observation(result.page, QueryWalkCoverage.Complete)

        fun from(result: TraversalResult.Qualified): QueryWalkObservation =
            observation(result.page, QueryWalkCoverage.from(result.qualification))

        private fun observation(page: TraversalPage, coverage: QueryWalkCoverage): QueryWalkObservation =
            QueryWalkObservation(
                subject = page.plan.start,
                meaning = page.plan.meaning,
                budget = page.plan.budget,
                strategy = page.plan.strategy,
                progress = page.progress,
                expandedFrontier = page.expandedFrontier,
                partialExpansions =
                    java.util.Collections.unmodifiableList(page.partialExpansions.map(QueryWalkPartialExpansion::from)),
                coverage = coverage,
            )
    }
}

private fun <Value> Set<Value>.immutableOrderedBy(order: (Value) -> Int): List<Value> =
    java.util.Collections.unmodifiableList(sortedBy(order))
