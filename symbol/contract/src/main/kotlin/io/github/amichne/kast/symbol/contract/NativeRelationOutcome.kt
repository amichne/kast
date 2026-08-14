package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement

enum class NativeRelationMeasureFailure {
    NEGATIVE,
}

@JvmInline
value class NativeRelationByteCount private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * Long to Refinement<NativeRelationByteCount, NativeRelationMeasureFailure>.
         *
         * Establishes a non-negative canonical fact-projection byte count.
         * [NativeRelationMeasureFailure] is the closed expected failure. Raw counts may be
         * extracted only at bounded projection, metrics, or transport boundaries.
         */
        fun parse(
            raw: Long,
        ): Refinement<NativeRelationByteCount, NativeRelationMeasureFailure> =
            if (raw >= 0L) {
                Refinement.Refined(NativeRelationByteCount(raw))
            } else {
                Refinement.Rejected(NativeRelationMeasureFailure.NEGATIVE)
            }
    }
}

@JvmInline
value class NativeRelationWorkCount private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * Long to Refinement<NativeRelationWorkCount, NativeRelationMeasureFailure>.
         *
         * Establishes a non-negative count of native relation items examined.
         * [NativeRelationMeasureFailure] is the closed expected failure. Raw counts may be
         * extracted only at native query, metrics, or transport boundaries.
         */
        fun parse(
            raw: Long,
        ): Refinement<NativeRelationWorkCount, NativeRelationMeasureFailure> =
            if (raw >= 0L) {
                Refinement.Refined(NativeRelationWorkCount(raw))
            } else {
                Refinement.Rejected(NativeRelationMeasureFailure.NEGATIVE)
            }
    }
}

@JvmInline
value class NativeRelationElapsedNanoseconds private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * Long to Refinement<NativeRelationElapsedNanoseconds, NativeRelationMeasureFailure>.
         *
         * Establishes a non-negative monotonic elapsed duration.
         * [NativeRelationMeasureFailure] is the closed expected failure. Raw durations may be
         * extracted only at native timing, metrics, or transport boundaries.
         */
        fun parse(
            raw: Long,
        ): Refinement<NativeRelationElapsedNanoseconds, NativeRelationMeasureFailure> =
            if (raw >= 0L) {
                Refinement.Refined(NativeRelationElapsedNanoseconds(raw))
            } else {
                Refinement.Rejected(NativeRelationMeasureFailure.NEGATIVE)
            }
    }
}

data class NativeRelationTimings(
    val nativeQuery: NativeRelationElapsedNanoseconds,
    val projection: NativeRelationElapsedNanoseconds,
)

enum class NativeRelationBatchFailure {
    SUBJECT_MISMATCH,
    FAMILY_MISMATCH,
    RESULT_LIMIT_EXCEEDED,
    BYTE_LIMIT_EXCEEDED,
    NON_DETERMINISTIC_ORDER,
    ENCODED_BYTE_COUNT_MISMATCH,
}

@ConsistentCopyVisibility
data class NativeRelationBatch private constructor(
    val request: NativeRelationRequest,
    val facts: List<NativeRelationFact>,
    val encodedBytes: NativeRelationByteCount,
    val examinedWorkUnits: NativeRelationWorkCount,
    val timings: NativeRelationTimings,
) {
    companion object {
        /**
         * Proof transition:
         * NativeRelationRequest + facts + measures to
         * Refinement<NativeRelationBatch, NativeRelationBatchFailure>.
         *
         * Establishes that every fact retains the exact request selector and family, facts are
         * unique and deterministically ordered, and the batch fits the record and canonical byte
         * limits. [NativeRelationBatchFailure] is the closed expected failure. Lists and raw
         * measures may be extracted only at bounded native projection or transport boundaries.
         */
        fun create(
            request: NativeRelationRequest,
            facts: List<NativeRelationFact>,
            encodedBytes: NativeRelationByteCount,
            examinedWorkUnits: NativeRelationWorkCount,
            timings: NativeRelationTimings,
        ): Refinement<NativeRelationBatch, NativeRelationBatchFailure> {
            if (facts.any { it.subject !== request.selector }) {
                return Refinement.Rejected(NativeRelationBatchFailure.SUBJECT_MISMATCH)
            }
            if (facts.any { it.family != request.family }) {
                return Refinement.Rejected(NativeRelationBatchFailure.FAMILY_MISMATCH)
            }
            if (facts.size > request.budget.resources.resultLimit.value) {
                return Refinement.Rejected(NativeRelationBatchFailure.RESULT_LIMIT_EXCEEDED)
            }
            if (encodedBytes.value > request.budget.returnedBytes.value) {
                return Refinement.Rejected(NativeRelationBatchFailure.BYTE_LIMIT_EXCEEDED)
            }
            if (facts != facts.distinct().sorted()) {
                return Refinement.Rejected(NativeRelationBatchFailure.NON_DETERMINISTIC_ORDER)
            }
            if (facts.sumOf { it.projectedUtf8Size().value } != encodedBytes.value) {
                return Refinement.Rejected(
                    NativeRelationBatchFailure.ENCODED_BYTE_COUNT_MISMATCH,
                )
            }
            return Refinement.Refined(
                NativeRelationBatch(
                    request,
                    facts.toList(),
                    encodedBytes,
                    examinedWorkUnits,
                    timings,
                ),
            )
        }
    }
}

enum class NativeRelationLimitation {
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DUMB_MODE_TRANSITION,
    UNRESOLVED_TARGET,
    UNSUPPORTED_ITEM,
    PROVIDER_FAILURE,
    PROVIDER_INCOMPLETE,
}

enum class NativeRelationLimitationsFailure {
    EMPTY,
}

class NativeRelationLimitations private constructor(
    val values: Set<NativeRelationLimitation>,
) {
    companion object {
        /**
         * Proof transition:
         * Set<NativeRelationLimitation> to
         * Refinement<NativeRelationLimitations, NativeRelationLimitationsFailure>.
         *
         * Establishes a non-empty canonical closed limitation set.
         * [NativeRelationLimitationsFailure] is the closed expected failure. Raw sets may be
         * extracted only at native provider and transport boundaries.
         */
        fun from(
            raw: Set<NativeRelationLimitation>,
        ): Refinement<NativeRelationLimitations, NativeRelationLimitationsFailure> =
            if (raw.isEmpty()) {
                Refinement.Rejected(NativeRelationLimitationsFailure.EMPTY)
            } else {
                Refinement.Refined(
                    NativeRelationLimitations(
                        raw.toSortedSet(compareBy { it.ordinal }).toSet(),
                    ),
                )
            }
    }

    override fun equals(other: Any?): Boolean =
        other is NativeRelationLimitations && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

@JvmInline
value class NativeRelationExactCount internal constructor(
    val value: Int,
)

@JvmInline
value class NativeRelationKnownMinimumCount internal constructor(
    val value: Int,
)

sealed interface NativeRelationOutcome {
    class Complete internal constructor(
        val batch: NativeRelationBatch,
        val exactCount: NativeRelationExactCount,
    ) : NativeRelationOutcome

    class Qualified internal constructor(
        val batch: NativeRelationBatch,
        val knownMinimumCount: NativeRelationKnownMinimumCount,
        val limitations: NativeRelationLimitations,
    ) : NativeRelationOutcome

    companion object {
        /**
         * Proof transition:
         * terminal NativeRelationBatch to NativeRelationOutcome.Complete.
         *
         * Establishes exact cardinality equal to the terminal batch size. Only a native provider
         * that proved terminal enumeration may call this transition.
         */
        fun complete(
            batch: NativeRelationBatch,
        ): Complete = Complete(batch, NativeRelationExactCount(batch.facts.size))

        /**
         * Proof transition:
         * NativeRelationBatch + non-empty limitations to
         * Refinement<NativeRelationOutcome.Qualified, NativeRelationLimitationsFailure>.
         *
         * Establishes known-minimum cardinality equal to the retained batch size without claiming
         * absence beyond incomplete coverage. [NativeRelationLimitationsFailure] is the closed
         * expected failure. Only bounded native query and transport boundaries may inspect counts.
         */
        fun qualified(
            batch: NativeRelationBatch,
            limitations: Set<NativeRelationLimitation>,
        ): Refinement<Qualified, NativeRelationLimitationsFailure> =
            when (val refined = NativeRelationLimitations.from(limitations)) {
                is Refinement.Refined ->
                    Refinement.Refined(
                        Qualified(
                            batch,
                            NativeRelationKnownMinimumCount(batch.facts.size),
                            refined.value,
                        ),
                    )
                is Refinement.Rejected -> refined
            }
    }
}
