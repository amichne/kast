package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadRejection
import java.nio.charset.StandardCharsets

enum class TraversalRecordFailure {
    SUBJECT_MISMATCH,
    MEANING_MISMATCH,
    GENERATION_MISMATCH,
    SCOPE_MISMATCH,
    NON_RELATED_ENDPOINT,
    DEPTH_EXCEEDS_PLAN,
}

/** One exact relation fact at its deterministic breadth-first traversal depth. */
@ConsistentCopyVisibility
data class TraversalRecord private constructor(
    val origin: RelationEndpointFingerprint,
    val depth: TraversalDepth,
    val fact: RelationFact,
    val related: RelationEndpoint.Resolved,
) : Comparable<TraversalRecord> {
    override fun compareTo(other: TraversalRecord): Int =
        compareValuesBy(this, other, TraversalRecord::depth, { it.origin.value }, { it.fact })

    fun canonicalProjection(): String = buildString {
        appendTraversalField(depth.value.toString())
        appendTraversalField(origin.value)
        appendTraversalField(fact.canonicalProjection())
    }

    companion object {
        /**
         * Proof transition: `(TraversalPlan, origin, TraversalDepth, RelationFact) ->
         * Refinement<TraversalRecord, TraversalRecordFailure>`.
         *
         * Establishes the plan's exact subject, meaning, root, generation, scope, hop depth, and
         * one compiler-grounded related endpoint. [TraversalRecordFailure] is the closed expected
         * failure. Raw relation facts may enter only from the module-private one-hop reader.
         */
        fun create(
            plan: TraversalPlan,
            origin: RelationEndpointFingerprint,
            depth: TraversalDepth,
            fact: RelationFact,
        ): Refinement<TraversalRecord, TraversalRecordFailure> {
            if (fact.subject.fingerprint.value != origin.value) {
                return Refinement.Rejected(TraversalRecordFailure.SUBJECT_MISMATCH)
            }
            if (fact.meaning != plan.meaning) {
                return Refinement.Rejected(TraversalRecordFailure.MEANING_MISMATCH)
            }
            if (fact.generation != plan.start.lease.generation) {
                return Refinement.Rejected(TraversalRecordFailure.GENERATION_MISMATCH)
            }
            if (fact.source.scope != plan.scope || fact.target.scope != plan.scope) {
                return Refinement.Rejected(TraversalRecordFailure.SCOPE_MISMATCH)
            }
            if (depth.value > plan.budget.depth.value) {
                return Refinement.Rejected(TraversalRecordFailure.DEPTH_EXCEEDS_PLAN)
            }
            val related = when (plan.meaning) {
                RelationMeaning.Callees -> fact.target
                RelationMeaning.References,
                RelationMeaning.Callers,
                RelationMeaning.Implementations,
                RelationMeaning.Inheritors,
                RelationMeaning.Overrides,
                RelationMeaning.TypeUses,
                    -> fact.source
            }
            return if (related is RelationEndpoint.Resolved) {
                Refinement.Refined(TraversalRecord(origin, depth, fact, related))
            } else {
                Refinement.Rejected(TraversalRecordFailure.NON_RELATED_ENDPOINT)
            }
        }
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
}

@JvmInline
value class TraversalByteCount internal constructor(val value: Long)

@JvmInline
value class TraversalWorkCount internal constructor(val value: Long)

@JvmInline
value class TraversalElapsedMillis internal constructor(val value: Long)

@JvmInline
value class TraversalFrontierCount internal constructor(val value: Int)

@ConsistentCopyVisibility
data class TraversalPage private constructor(
    val plan: TraversalPlan,
    val records: List<TraversalRecord>,
    val encodedBytes: TraversalByteCount,
    val examinedWorkUnits: TraversalWorkCount,
    val elapsedMillis: TraversalElapsedMillis,
    val expandedFrontier: TraversalFrontierCount,
) {
    companion object {
        /**
         * Proof transition: `(TraversalPlan, List<TraversalRecord>, raw measures) ->
         * Refinement<TraversalPage, TraversalPageFailure>`.
         *
         * Establishes deterministic unique records and non-negative exact byte/work/time/frontier
         * measures inside every aggregate request bound. [TraversalPageFailure] is the closed
         * expected failure. Raw measures may enter only from pure traversal accounting or
         * continuation transport.
         */
        fun fromBoundary(
            plan: TraversalPlan,
            records: List<TraversalRecord>,
            encodedBytes: Long,
            examinedWorkUnits: Long,
            elapsedMillis: Long,
            expandedFrontier: Int,
        ): Refinement<TraversalPage, TraversalPageFailure> {
            if (
                encodedBytes < 0L || examinedWorkUnits < 0L || elapsedMillis < 0L ||
                expandedFrontier < 0
            ) {
                return Refinement.Rejected(TraversalPageFailure.NEGATIVE_MEASURE)
            }
            if (records != records.sorted()) {
                return Refinement.Rejected(TraversalPageFailure.NON_DETERMINISTIC_RECORDS)
            }
            if (records.distinct().size != records.size) {
                return Refinement.Rejected(TraversalPageFailure.DUPLICATE_RECORD)
            }
            if (records.size > plan.budget.records.value) {
                return Refinement.Rejected(TraversalPageFailure.RECORD_LIMIT_EXCEEDED)
            }
            if (encodedBytes > plan.budget.returnedBytes.value) {
                return Refinement.Rejected(TraversalPageFailure.BYTE_LIMIT_EXCEEDED)
            }
            if (examinedWorkUnits > plan.budget.workUnits.value) {
                return Refinement.Rejected(TraversalPageFailure.WORK_LIMIT_EXCEEDED)
            }
            if (elapsedMillis > plan.budget.elapsedTime.value) {
                return Refinement.Rejected(TraversalPageFailure.TIME_LIMIT_EXCEEDED)
            }
            if (expandedFrontier > plan.budget.frontier.value) {
                return Refinement.Rejected(TraversalPageFailure.FRONTIER_LIMIT_EXCEEDED)
            }
            val measured = records.sumOf { record ->
                record.fact.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
            }
            if (measured != encodedBytes) {
                return Refinement.Rejected(TraversalPageFailure.ENCODED_BYTE_COUNT_MISMATCH)
            }
            return Refinement.Refined(
                TraversalPage(
                    plan,
                    records.toList(),
                    TraversalByteCount(encodedBytes),
                    TraversalWorkCount(examinedWorkUnits),
                    TraversalElapsedMillis(elapsedMillis),
                    TraversalFrontierCount(expandedFrontier),
                ),
            )
        }
    }
}

enum class TraversalLimitation {
    RECORD_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DEPTH_LIMIT_REACHED,
    FRONTIER_LIMIT_REACHED,
    ONE_HOP_INCOMPLETE,
}

enum class TraversalQualificationFailure {
    EMPTY_LIMITATIONS,
    MISSING_RELATION_LIMITATION,
    UNEXPECTED_RELATION_LIMITATION,
    CONTINUATION_MISMATCH,
    TERMINAL_LIMITATION_RESUMABLE,
    TERMINAL_WITHOUT_TERMINAL_LIMITATION,
}

sealed interface TraversalQualification {
    val limitations: Set<TraversalLimitation>
    val relationLimitations: Set<RelationLimitation>

    class Resumable internal constructor(
        override val limitations: Set<TraversalLimitation>,
        override val relationLimitations: Set<RelationLimitation>,
        val continuation: TraversalContinuation,
    ) : TraversalQualification

    class TerminalIncomplete internal constructor(
        override val limitations: Set<TraversalLimitation>,
        override val relationLimitations: Set<RelationLimitation>,
    ) : TraversalQualification

    companion object {
        /**
         * Proof transition: `(TraversalPage, limitations, relation limitations,
         * TraversalContinuation) -> Refinement<TraversalQualification,
         * TraversalQualificationFailure>`.
         *
         * Establishes non-empty ordered qualification reasons, exact one-hop limitations when
         * applicable, and continuation identity equal to the page plan.
         * [TraversalQualificationFailure] is the closed expected failure. Raw limitation
         * collections may enter only from the pure traversal engine or transport.
         */
        fun resumable(
            page: TraversalPage,
            limitations: Set<TraversalLimitation>,
            relationLimitations: Set<RelationLimitation>,
            continuation: TraversalContinuation,
        ): Refinement<Resumable, TraversalQualificationFailure> = when (
            val admitted = admitTraversalLimitations(limitations, relationLimitations)
        ) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined -> if (
                TraversalLimitation.DEPTH_LIMIT_REACHED in admitted.value.first
            ) {
                Refinement.Rejected(
                    TraversalQualificationFailure.TERMINAL_LIMITATION_RESUMABLE,
                )
            } else if (continuation.identity != page.plan.identity) {
                Refinement.Rejected(TraversalQualificationFailure.CONTINUATION_MISMATCH)
            } else {
                Refinement.Refined(
                    Resumable(
                        admitted.value.first,
                        admitted.value.second,
                        continuation,
                    ),
                )
            }
        }

        fun terminalIncomplete(
            limitations: Set<TraversalLimitation>,
            relationLimitations: Set<RelationLimitation>,
        ): Refinement<TerminalIncomplete, TraversalQualificationFailure> = when (
            val admitted = admitTraversalLimitations(limitations, relationLimitations)
        ) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined -> if (
                TraversalLimitation.ONE_HOP_INCOMPLETE !in admitted.value.first &&
                TraversalLimitation.DEPTH_LIMIT_REACHED !in admitted.value.first
            ) {
                Refinement.Rejected(
                    TraversalQualificationFailure.TERMINAL_WITHOUT_TERMINAL_LIMITATION,
                )
            } else {
                Refinement.Refined(
                    TerminalIncomplete(
                        admitted.value.first,
                        admitted.value.second,
                    ),
                )
            }
        }
    }
}

private fun admitTraversalLimitations(
    limitations: Set<TraversalLimitation>,
    relationLimitations: Set<RelationLimitation>,
): Refinement<
    Pair<Set<TraversalLimitation>, Set<RelationLimitation>>,
    TraversalQualificationFailure,
    > = when {
    limitations.isEmpty() ->
        Refinement.Rejected(TraversalQualificationFailure.EMPTY_LIMITATIONS)
    TraversalLimitation.ONE_HOP_INCOMPLETE in limitations && relationLimitations.isEmpty() ->
        Refinement.Rejected(TraversalQualificationFailure.MISSING_RELATION_LIMITATION)
    TraversalLimitation.ONE_HOP_INCOMPLETE !in limitations && relationLimitations.isNotEmpty() ->
        Refinement.Rejected(TraversalQualificationFailure.UNEXPECTED_RELATION_LIMITATION)
    else -> Refinement.Refined(
        limitations.toSortedSet(compareBy { it.ordinal }).toSet() to
            relationLimitations.toSortedSet(compareBy { it.ordinal }).toSet(),
    )
}

@JvmInline
value class TraversalExactRecordCount internal constructor(val value: Int)

@ConsistentCopyVisibility
data class TraversalCompleteCoverage internal constructor(
    val exactRecordCount: TraversalExactRecordCount,
)

sealed interface TraversalRejection {
    data class OneHopRejected(val reason: RelationReadRejection) : TraversalRejection
    data object RequiredEvidenceUnavailable : TraversalRejection
    data object RequiredEvidenceStale : TraversalRejection
    data object ReaderContractViolation : TraversalRejection
    data object TraversalContractViolation : TraversalRejection
}

sealed interface TraversalResult {
    @ConsistentCopyVisibility
    data class Complete internal constructor(
        val page: TraversalPage,
        val coverage: TraversalCompleteCoverage,
    ) : TraversalResult

    @ConsistentCopyVisibility
    data class Qualified internal constructor(
        val page: TraversalPage,
        val qualification: TraversalQualification,
    ) : TraversalResult

    data class Rejected(val reason: TraversalRejection) : TraversalResult

    companion object {
        /**
         * Proof transition: `TraversalPage + exhausted deterministic frontier ->
         * TraversalResult.Complete`.
         *
         * Establishes exact record count after every one-hop page and the traversal frontier are
         * terminal. Raw provider state is not permitted at this boundary.
         */
        fun complete(page: TraversalPage): Complete = Complete(
            page,
            TraversalCompleteCoverage(TraversalExactRecordCount(page.records.size)),
        )

        /**
         * Proof transition: `(TraversalPage, limitations, relation limitations,
         * TraversalContinuation) -> Refinement<TraversalResult.Qualified,
         * TraversalQualificationFailure>`.
         *
         * Establishes a bounded partial traversal that cannot represent complete exhaustion and
         * retains exact deterministic resume state. [TraversalQualificationFailure] is the closed
         * expected failure. Raw limitations may enter only from the pure engine or transport.
         */
        fun qualifiedResumable(
            page: TraversalPage,
            limitations: Set<TraversalLimitation>,
            relationLimitations: Set<RelationLimitation>,
            continuation: TraversalContinuation,
        ): Refinement<Qualified, TraversalQualificationFailure> = when (
            val qualification = TraversalQualification.resumable(
                page,
                limitations,
                relationLimitations,
                continuation,
            )
        ) {
            is Refinement.Refined -> Refinement.Refined(Qualified(page, qualification.value))
            is Refinement.Rejected -> qualification
        }

        fun qualifiedTerminal(
            page: TraversalPage,
            limitations: Set<TraversalLimitation>,
            relationLimitations: Set<RelationLimitation>,
        ): Refinement<Qualified, TraversalQualificationFailure> = when (
            val qualification = TraversalQualification.terminalIncomplete(
                limitations,
                relationLimitations,
            )
        ) {
            is Refinement.Refined -> Refinement.Refined(Qualified(page, qualification.value))
            is Refinement.Rejected -> qualification
        }
    }
}
