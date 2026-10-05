package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationReadRejection
import java.nio.charset.StandardCharsets

@JvmInline value class TraversalExactRecordCount internal constructor(val value: Int)

@ConsistentCopyVisibility
data class TraversalCompleteCoverage internal constructor(val exactRecordCount: TraversalExactRecordCount)

sealed interface TraversalRejection {
    data class OneHopRejected(val reason: RelationReadRejection) : TraversalRejection

    data object RequiredEvidenceUnavailable : TraversalRejection

    data object RequiredEvidenceStale : TraversalRejection

    data object ReaderContractViolation : TraversalRejection

    data object TraversalContractViolation : TraversalRejection
}

sealed interface TraversalResult {
    @ConsistentCopyVisibility
    data class Complete
    internal constructor(
        val page: TraversalPage,
        val coverage: TraversalCompleteCoverage,
    ) : TraversalResult

    @ConsistentCopyVisibility
    data class Qualified
    internal constructor(
        val page: TraversalPage,
        val qualification: TraversalQualification,
    ) : TraversalResult

    data class Rejected(val reason: TraversalRejection) : TraversalResult

    companion object {
        /**
         * Proof transition: `TraversalPage + exhausted deterministic frontier -> TraversalResult.Complete`.
         *
         * Establishes exact record count after every one-hop page and the traversal frontier are terminal. Raw provider
         * state is not permitted at this boundary.
         */
        fun complete(page: TraversalPage): TraversalResult =
            if (page.elapsedMillis.value > page.plan.budget.elapsedTime.value) {
                Rejected(TraversalRejection.TraversalContractViolation)
            } else
                Complete(
                    page,
                    TraversalCompleteCoverage(TraversalExactRecordCount(page.records.size)),
                )

        /**
         * Proof transition: `(TraversalPage, limitations, relation limitations, TraversalContinuation) ->
         * Refinement<TraversalResult.Qualified, TraversalQualificationFailure>`.
         *
         * Establishes a bounded partial traversal that cannot represent complete exhaustion and retains exact
         * deterministic resume state. [TraversalQualificationFailure] is the closed expected failure. Raw limitations
         * may enter only from the pure engine or transport.
         */
        fun qualifiedResumable(
            page: TraversalPage,
            limitations: Set<TraversalLimitation>,
            relationLimitations: Set<RelationLimitation>,
            continuation: TraversalContinuation,
        ): Refinement<Qualified, TraversalQualificationFailure> =
            when (
                val qualification =
                    TraversalQualification.resumable(
                        page,
                        limitations,
                        relationLimitations,
                        continuation,
                    )
            ) {
                is Refinement.Refined -> admitQualifiedPage(page, qualification.value)
                is Refinement.Rejected -> qualification
            }

        fun qualifiedTerminal(
            page: TraversalPage,
            limitations: Set<TraversalLimitation>,
            relationLimitations: Set<RelationLimitation>,
        ): Refinement<Qualified, TraversalQualificationFailure> =
            when (
                val qualification =
                    TraversalQualification.terminalIncomplete(
                        limitations,
                        relationLimitations,
                    )
            ) {
                is Refinement.Refined -> admitQualifiedPage(page, qualification.value)
                is Refinement.Rejected -> qualification
            }

        private fun admitQualifiedPage(
            page: TraversalPage,
            qualification: TraversalQualification,
        ): Refinement<Qualified, TraversalQualificationFailure> =
            if (
                page.elapsedMillis.value > page.plan.budget.elapsedTime.value &&
                    TraversalLimitation.TIME_LIMIT_REACHED !in qualification.limitations
            )
                Refinement.Rejected(TraversalQualificationFailure.ELAPSED_OVERRUN_UNQUALIFIED)
            else Refinement.Refined(Qualified(page, qualification))
    }
}

enum class TraversalPageFailure {
    NEGATIVE_MEASURE,
    NON_DETERMINISTIC_RECORDS,
    DUPLICATE_RECORD,
    RECORD_LIMIT_EXCEEDED,
    BYTE_LIMIT_EXCEEDED,
    WORK_LIMIT_EXCEEDED,
    TIME_LIMIT_EXCEEDED,
    FRONTIER_LIMIT_EXCEEDED,
    ENCODED_BYTE_COUNT_MISMATCH,
    PARTIAL_EXPANSION_MISMATCH,
    SCOPE_EXCLUSION_MISMATCH,
    CALLBACK_OBSERVATION_MISMATCH,
}

@JvmInline value class TraversalByteCount internal constructor(val value: Long)

@JvmInline value class TraversalWorkCount internal constructor(val value: Long)

@JvmInline value class TraversalElapsedMillis internal constructor(val value: Long)

@JvmInline value class TraversalFrontierCount internal constructor(val value: Int)

@ConsistentCopyVisibility
data class TraversalPage
private constructor(
    val plan: TraversalPlan,
    val records: List<TraversalRecord>,
    val encodedBytes: TraversalByteCount,
    val examinedWorkUnits: TraversalWorkCount,
    val elapsedMillis: TraversalElapsedMillis,
    val expandedFrontier: TraversalFrontierCount,
    val progress: TraversalProgress,
    val partialExpansions: List<TraversalPartialExpansion>,
    val inheritedOmissions: List<TraversalPartialExpansion>,
    val referenceOccurrences: List<TraversalReferenceObservation>,
    val scopeExclusions: List<TraversalScopeExclusion>,
    val callbackObservations: List<TraversalCallbackObservation>,
) {
    companion object {
        /**
         * Proof transition: `(TraversalPlan, List<TraversalRecord>, raw measures) -> Refinement<TraversalPage,
         * TraversalPageFailure>`.
         *
         * Establishes deterministic unique records and non-negative exact byte/work/frontier measures inside every
         * aggregate request bound. Elapsed time is an observation and can exceed its scheduling grant; result admission
         * requires a time qualification for that overrun. [TraversalPageFailure] is the closed expected failure. Raw
         * measures may enter only from pure traversal accounting or continuation transport.
         */
        fun fromBoundary(
            plan: TraversalPlan,
            records: List<TraversalRecord>,
            encodedBytes: Long,
            examinedWorkUnits: Long,
            elapsedMillis: Long,
            expandedFrontier: Int,
            progress: TraversalProgress = TraversalProgress.Initial,
            partialExpansions: List<TraversalPartialExpansion> = emptyList(),
            inheritedOmissions: List<TraversalPartialExpansion> = emptyList(),
            referenceOccurrences: List<TraversalReferenceObservation> = emptyList(),
            scopeExclusions: List<TraversalScopeExclusion> = emptyList(),
            callbackObservations: List<TraversalCallbackObservation> = emptyList(),
        ): Refinement<TraversalPage, TraversalPageFailure> {
            when (
                val measures = admitMeasures(plan, encodedBytes, examinedWorkUnits, elapsedMillis, expandedFrontier)
            ) {
                is Refinement.Rejected -> return measures
                is Refinement.Refined -> Unit
            }
            when (
                val contents =
                    admitContents(
                        plan,
                        records,
                        encodedBytes,
                        expandedFrontier,
                        partialExpansions,
                        referenceOccurrences,
                        scopeExclusions,
                        callbackObservations,
                    )
            ) {
                is Refinement.Rejected -> return contents
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(
                TraversalPage(
                    plan = plan,
                    records = records.toList(),
                    encodedBytes = TraversalByteCount(encodedBytes),
                    examinedWorkUnits = TraversalWorkCount(examinedWorkUnits),
                    elapsedMillis = TraversalElapsedMillis(elapsedMillis),
                    expandedFrontier = TraversalFrontierCount(expandedFrontier),
                    progress = progress,
                    partialExpansions = partialExpansions.sortedBy { it.entry }.toList(),
                    inheritedOmissions = java.util.Collections.unmodifiableList(inheritedOmissions.distinct().toList()),
                    referenceOccurrences = java.util.Collections.unmodifiableList(referenceOccurrences.toList()),
                    scopeExclusions = java.util.Collections.unmodifiableList(scopeExclusions.toList()),
                    callbackObservations = java.util.Collections.unmodifiableList(callbackObservations.toList()),
                )
            )
        }

        private fun admitMeasures(
            plan: TraversalPlan,
            encodedBytes: Long,
            examinedWorkUnits: Long,
            elapsedMillis: Long,
            expandedFrontier: Int,
        ): Refinement<Unit, TraversalPageFailure> =
            when {
                listOf(encodedBytes, examinedWorkUnits, elapsedMillis, expandedFrontier.toLong()).any { it < 0L } ->
                    Refinement.Rejected(TraversalPageFailure.NEGATIVE_MEASURE)
                encodedBytes > plan.budget.returnedBytes.value ->
                    Refinement.Rejected(TraversalPageFailure.BYTE_LIMIT_EXCEEDED)
                examinedWorkUnits > plan.budget.workUnits.value ->
                    Refinement.Rejected(TraversalPageFailure.WORK_LIMIT_EXCEEDED)
                expandedFrontier > plan.budget.frontier.value ->
                    Refinement.Rejected(TraversalPageFailure.FRONTIER_LIMIT_EXCEEDED)
                else -> Refinement.Refined(Unit)
            }

        private fun admitContents(
            plan: TraversalPlan,
            records: List<TraversalRecord>,
            encodedBytes: Long,
            expandedFrontier: Int,
            partialExpansions: List<TraversalPartialExpansion>,
            referenceOccurrences: List<TraversalReferenceObservation>,
            scopeExclusions: List<TraversalScopeExclusion>,
            callbackObservations: List<TraversalCallbackObservation>,
        ): Refinement<Unit, TraversalPageFailure> {
            when (
                val admitted =
                    admitScopeOutcomes(plan, records, referenceOccurrences, scopeExclusions, callbackObservations)
            ) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            if (records != records.sorted()) return Refinement.Rejected(TraversalPageFailure.NON_DETERMINISTIC_RECORDS)
            if (records.distinct().size != records.size)
                return Refinement.Rejected(TraversalPageFailure.DUPLICATE_RECORD)
            when (val admitted = admitReferences(plan, records, referenceOccurrences)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            if (
                partialExpansions.size > expandedFrontier ||
                    partialExpansions.map { it.entry.node.fingerprint }.distinct().size != partialExpansions.size
            )
                return Refinement.Rejected(TraversalPageFailure.PARTIAL_EXPANSION_MISMATCH)
            if (
                partialExpansions.any { partial ->
                    partial.entry.node.endpoint.lease != plan.start.lease ||
                        !plan.admitsEndpoint(partial.entry.node.endpoint)
                }
            )
                return Refinement.Rejected(TraversalPageFailure.PARTIAL_EXPANSION_MISMATCH)
            val measured =
                records.sumOf { record ->
                    record.fact.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
                } +
                    referenceOccurrences.sumOf {
                        it.reference.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
                    } +
                    callbackObservations.sumOf {
                        it.observation.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
                    } +
                    scopeExclusions.sumOf {
                        it.exclusion.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
                    }
            return if (measured != encodedBytes) Refinement.Rejected(TraversalPageFailure.ENCODED_BYTE_COUNT_MISMATCH)
            else Refinement.Refined(Unit)
        }

        private fun admitScopeOutcomes(
            plan: TraversalPlan,
            records: List<TraversalRecord>,
            references: List<TraversalReferenceObservation>,
            exclusions: List<TraversalScopeExclusion>,
            callbacks: List<TraversalCallbackObservation>,
        ): Refinement<Unit, TraversalPageFailure> {
            if (
                exclusions != exclusions.distinct().sorted() ||
                    exclusions.any {
                        TraversalScopeExclusion.create(plan, it.entry, it.exclusion) !is Refinement.Refined
                    }
            )
                return Refinement.Rejected(TraversalPageFailure.SCOPE_EXCLUSION_MISMATCH)
            if (
                callbacks != callbacks.distinct().sorted() ||
                    callbacks.any {
                        TraversalCallbackObservation.create(plan, it.entry, it.observation) !is Refinement.Refined
                    }
            )
                return Refinement.Rejected(TraversalPageFailure.CALLBACK_OBSERVATION_MISMATCH)
            val outcomeCount =
                records.size +
                    references.count { reference ->
                        records.none {
                            it.fact.occurrence == reference.reference.occurrence &&
                                it.fact.target == reference.reference.target
                        }
                    } +
                    exclusions.size +
                    callbacks.count { callback ->
                        records.none { it.fact.occurrence == callback.observation.occurrence }
                    }
            return if (outcomeCount > plan.budget.records.value)
                Refinement.Rejected(TraversalPageFailure.RECORD_LIMIT_EXCEEDED)
            else Refinement.Refined(Unit)
        }

        private fun admitReferences(
            plan: TraversalPlan,
            records: List<TraversalRecord>,
            references: List<TraversalReferenceObservation>,
        ): Refinement<Unit, TraversalPageFailure> {
            val semanticCount =
                records.size +
                    references.count { observation ->
                        records.none {
                            it.fact.occurrence == observation.reference.occurrence &&
                                it.fact.target == observation.reference.target
                        }
                    }
            if (semanticCount > plan.budget.records.value)
                return Refinement.Rejected(TraversalPageFailure.RECORD_LIMIT_EXCEEDED)
            if (references != references.distinct().sorted())
                return Refinement.Rejected(TraversalPageFailure.PARTIAL_EXPANSION_MISMATCH)
            if (
                references.any {
                    it.reference.authority != plan.start.lease.identity || it.reference.meaning != plan.meaning
                }
            )
                return Refinement.Rejected(TraversalPageFailure.PARTIAL_EXPANSION_MISMATCH)
            return Refinement.Refined(Unit)
        }
    }
}
