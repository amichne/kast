package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationKnownMinimum
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
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
    val knownMinimum: RelationKnownMinimum,
    val omissions: List<RelationOmissionEvidence>,
    val remainder: TraversalExpansionRemainder,
) {
    companion object {
        internal fun from(expansion: TraversalPartialExpansion): QueryWalkPartialExpansion =
            QueryWalkPartialExpansion(
                expansion.entry,
                expansion.limitations.immutableOrderedBy { it.ordinal },
                expansion.knownMinimum,
                expansion.omissions,
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
    val question: QueryRelationQuestion,
    val budget: TraversalBudget,
    val strategy: TraversalStrategy,
    val progress: TraversalProgress,
    val expandedFrontier: TraversalFrontierCount,
    val partialExpansions: List<QueryWalkPartialExpansion>,
    val coverage: QueryWalkCoverage,
    val inheritedOmissions: List<QueryWalkPartialExpansion>,
    val referenceOccurrences: List<io.github.amichne.kast.traversal.contract.TraversalReferenceObservation>,
    val scopeExclusions: List<io.github.amichne.kast.traversal.contract.TraversalScopeExclusion>,
    val callbackObservations: List<io.github.amichne.kast.traversal.contract.TraversalCallbackObservation>,
) {
    /** Partition independent proof payloads before output accounting; their shared progress is a witness, not a sum. */
    fun evidenceUnits(): List<QueryWalkObservation> {
        if (
            partialExpansions.size +
                inheritedOmissions.size +
                referenceOccurrences.size +
                scopeExclusions.size +
                callbackObservations.size <= 1
        )
            return listOf(this)
        val structural =
            copy(
                partialExpansions = emptyList(),
                inheritedOmissions = emptyList(),
                referenceOccurrences = emptyList(),
                scopeExclusions = emptyList(),
                callbackObservations = emptyList(),
            )
        val units =
            partialExpansions.map { structural.copy(partialExpansions = listOf(it)) } +
                inheritedOmissions.map { structural.copy(inheritedOmissions = listOf(it)) } +
                referenceOccurrences.map { structural.copy(referenceOccurrences = listOf(it)) } +
                scopeExclusions.map { structural.copy(scopeExclusions = listOf(it)) } +
                callbackObservations.map { structural.copy(callbackObservations = listOf(it)) }
        return java.util.Collections.unmodifiableList(units)
    }

    companion object {
        fun from(result: TraversalResult.Complete): QueryWalkObservation =
            observation(result.page, QueryWalkCoverage.Complete)

        fun from(result: TraversalResult.Qualified): QueryWalkObservation =
            observation(result.page, QueryWalkCoverage.from(result.qualification))

        private fun observation(page: TraversalPage, coverage: QueryWalkCoverage): QueryWalkObservation =
            QueryWalkObservation(
                subject = page.plan.start,
                meaning = page.plan.meaning,
                question =
                    page.plan.let { plan ->
                        val endpoint = io.github.amichne.kast.relation.contract.RelationEndpoint.subject(plan.start)
                        QueryRelationQuestion(
                            endpoint,
                            plan.meaning,
                            plan.expansion,
                            plan.scope,
                            plan.expansion.effectiveConstraints(endpoint),
                            io.github.amichne.kast.relation.contract.RelationScopeFingerprint.from(
                                endpoint,
                                plan.expansion,
                            ),
                        )
                    },
                budget = page.plan.budget,
                strategy = page.plan.strategy,
                progress = page.progress,
                expandedFrontier = page.expandedFrontier,
                partialExpansions =
                    java.util.Collections.unmodifiableList(page.partialExpansions.map(QueryWalkPartialExpansion::from)),
                coverage = coverage,
                inheritedOmissions =
                    java.util.Collections.unmodifiableList(
                        page.inheritedOmissions.map(QueryWalkPartialExpansion::from)
                    ),
                referenceOccurrences = page.referenceOccurrences,
                scopeExclusions = page.scopeExclusions,
                callbackObservations = page.callbackObservations,
            )
    }
}

private fun <Value> Set<Value>.immutableOrderedBy(order: (Value) -> Int): List<Value> =
    java.util.Collections.unmodifiableList(sortedBy(order))
