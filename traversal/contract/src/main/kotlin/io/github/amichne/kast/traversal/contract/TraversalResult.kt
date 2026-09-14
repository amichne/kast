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
        ): Refinement<TraversalPage, TraversalPageFailure> {
            when (
                val measures = admitMeasures(plan, encodedBytes, examinedWorkUnits, elapsedMillis, expandedFrontier)
            ) {
                is Refinement.Rejected -> return measures
                is Refinement.Refined -> Unit
            }
            when (val contents = admitContents(plan, records, encodedBytes, expandedFrontier, partialExpansions)) {
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
        ): Refinement<Unit, TraversalPageFailure> {
            if (records != records.sorted()) return Refinement.Rejected(TraversalPageFailure.NON_DETERMINISTIC_RECORDS)
            if (records.distinct().size != records.size)
                return Refinement.Rejected(TraversalPageFailure.DUPLICATE_RECORD)
            if (records.size > plan.budget.records.value)
                return Refinement.Rejected(TraversalPageFailure.RECORD_LIMIT_EXCEEDED)
            if (
                partialExpansions.size > expandedFrontier ||
                    partialExpansions.map { it.entry.node.fingerprint }.distinct().size != partialExpansions.size
            )
                return Refinement.Rejected(TraversalPageFailure.PARTIAL_EXPANSION_MISMATCH)
            if (
                partialExpansions.any { partial ->
                    partial.entry.node.endpoint.lease != plan.start.lease ||
                        partial.entry.node.endpoint.scope != plan.scope
                }
            )
                return Refinement.Rejected(TraversalPageFailure.PARTIAL_EXPANSION_MISMATCH)
            val measured = records.sumOf { record ->
                record.fact.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
            }
            return if (measured != encodedBytes) Refinement.Rejected(TraversalPageFailure.ENCODED_BYTE_COUNT_MISMATCH)
            else Refinement.Refined(Unit)
        }
    }
}
