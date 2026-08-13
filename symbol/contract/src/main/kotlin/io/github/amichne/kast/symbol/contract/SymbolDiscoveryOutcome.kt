package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadLease

enum class SymbolDiscoveryMeasureFailure {
    NEGATIVE,
}

@JvmInline
value class SymbolDiscoveryByteCount private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * Long to Refinement<SymbolDiscoveryByteCount, SymbolDiscoveryMeasureFailure>.
         *
         * Establishes a non-negative canonical candidate-projection byte count.
         * [SymbolDiscoveryMeasureFailure] is the closed expected failure. Raw counts may be
         * extracted only at bounded projection, metrics, or transport boundaries.
         */
        fun parse(raw: Long): Refinement<SymbolDiscoveryByteCount, SymbolDiscoveryMeasureFailure> =
            if (raw >= 0L) {
                Refinement.Refined(SymbolDiscoveryByteCount(raw))
            } else {
                Refinement.Rejected(SymbolDiscoveryMeasureFailure.NEGATIVE)
            }
    }
}

@JvmInline
value class SymbolDiscoveryWorkCount private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * Long to Refinement<SymbolDiscoveryWorkCount, SymbolDiscoveryMeasureFailure>.
         *
         * Establishes a non-negative count of native names and elements examined by discovery.
         * [SymbolDiscoveryMeasureFailure] is the closed expected failure. Raw counts may be
         * extracted only at native query, metrics, or transport boundaries.
         */
        fun parse(raw: Long): Refinement<SymbolDiscoveryWorkCount, SymbolDiscoveryMeasureFailure> =
            if (raw >= 0L) {
                Refinement.Refined(SymbolDiscoveryWorkCount(raw))
            } else {
                Refinement.Rejected(SymbolDiscoveryMeasureFailure.NEGATIVE)
            }
    }
}

@JvmInline
value class SymbolDiscoveryElapsedNanoseconds private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * Long to Refinement<SymbolDiscoveryElapsedNanoseconds, SymbolDiscoveryMeasureFailure>.
         *
         * Establishes a non-negative monotonic elapsed duration in nanoseconds.
         * [SymbolDiscoveryMeasureFailure] is the closed expected failure. Raw durations may be
         * extracted only at the native timing and transport boundaries.
         */
        fun parse(
            raw: Long,
        ): Refinement<SymbolDiscoveryElapsedNanoseconds, SymbolDiscoveryMeasureFailure> =
            if (raw >= 0L) {
                Refinement.Refined(SymbolDiscoveryElapsedNanoseconds(raw))
            } else {
                Refinement.Rejected(SymbolDiscoveryMeasureFailure.NEGATIVE)
            }
    }
}

data class SymbolDiscoveryTimings(
    val nativeQuery: SymbolDiscoveryElapsedNanoseconds,
    val projection: SymbolDiscoveryElapsedNanoseconds,
)

enum class SymbolDiscoveryBatchFailure {
    CANDIDATE_LEASE_MISMATCH,
    RESULT_LIMIT_EXCEEDED,
    BYTE_LIMIT_EXCEEDED,
    NON_DETERMINISTIC_ORDER,
    ENCODED_BYTE_COUNT_MISMATCH,
}

@ConsistentCopyVisibility
data class SymbolDiscoveryBatch private constructor(
    val lease: SemanticReadLease,
    val scope: SymbolSearchScope,
    val candidates: List<SymbolDiscoveryCandidate>,
    val encodedBytes: SymbolDiscoveryByteCount,
    val examinedWorkUnits: SymbolDiscoveryWorkCount,
    val timings: SymbolDiscoveryTimings,
) {
    companion object {
        /**
         * Proof transition:
         * SymbolDiscoveryRequest + candidates + measures to
         * Refinement<SymbolDiscoveryBatch, SymbolDiscoveryBatchFailure>.
         *
         * Establishes that candidates retain the request lease, are unique and deterministically
         * ordered, and fit both the request record and canonical UTF-8 byte limits.
         * [SymbolDiscoveryBatchFailure] is the closed expected failure. Candidate lists and raw
         * metrics may be extracted only by bounded native projection or transport boundaries.
         */
        fun create(
            request: SymbolDiscoveryRequest,
            candidates: List<SymbolDiscoveryCandidate>,
            encodedBytes: SymbolDiscoveryByteCount,
            examinedWorkUnits: SymbolDiscoveryWorkCount,
            timings: SymbolDiscoveryTimings,
        ): Refinement<SymbolDiscoveryBatch, SymbolDiscoveryBatchFailure> {
            if (candidates.any { it.lease != request.scope.lease }) {
                return Refinement.Rejected(SymbolDiscoveryBatchFailure.CANDIDATE_LEASE_MISMATCH)
            }
            if (candidates.size > request.budget.resources.resultLimit.value) {
                return Refinement.Rejected(SymbolDiscoveryBatchFailure.RESULT_LIMIT_EXCEEDED)
            }
            if (encodedBytes.value > request.budget.returnedBytes.value) {
                return Refinement.Rejected(SymbolDiscoveryBatchFailure.BYTE_LIMIT_EXCEEDED)
            }
            if (candidates != candidates.distinct().sorted()) {
                return Refinement.Rejected(SymbolDiscoveryBatchFailure.NON_DETERMINISTIC_ORDER)
            }
            if (candidates.sumOf { it.projectedUtf8Size().value } != encodedBytes.value) {
                return Refinement.Rejected(SymbolDiscoveryBatchFailure.ENCODED_BYTE_COUNT_MISMATCH)
            }
            return Refinement.Refined(
                SymbolDiscoveryBatch(
                    lease = request.scope.lease,
                    scope = request.scope.scope,
                    candidates = candidates.toList(),
                    encodedBytes = encodedBytes,
                    examinedWorkUnits = examinedWorkUnits,
                    timings = timings,
                ),
            )
        }
    }
}

enum class SymbolDiscoveryQualification {
    RESULT_LIMIT_REACHED,
    BYTE_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    DUMB_MODE_TRANSITION,
    PROVIDER_FAILURE,
    UNSCOPED_PROVIDER,
    UNSUPPORTED_ITEM,
}

enum class SymbolDiscoveryQualificationFailure {
    EMPTY,
}

class SymbolDiscoveryQualifications private constructor(
    val values: Set<SymbolDiscoveryQualification>,
) {
    companion object {
        /**
         * Proof transition:
         * Set<SymbolDiscoveryQualification> to
         * Refinement<SymbolDiscoveryQualifications, SymbolDiscoveryQualificationFailure>.
         *
         * Establishes a non-empty closed qualification set, preventing a partial batch from being
         * represented without its limitation. [SymbolDiscoveryQualificationFailure] is the closed
         * expected failure. Raw sets may be extracted only at adapter and transport boundaries.
         */
        fun from(
            raw: Set<SymbolDiscoveryQualification>,
        ): Refinement<SymbolDiscoveryQualifications, SymbolDiscoveryQualificationFailure> =
            if (raw.isEmpty()) {
                Refinement.Rejected(SymbolDiscoveryQualificationFailure.EMPTY)
            } else {
                Refinement.Refined(SymbolDiscoveryQualifications(raw.toSet()))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is SymbolDiscoveryQualifications && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.toString()
}

sealed interface SymbolDiscoveryOutcome {
    data class Complete(
        val batch: SymbolDiscoveryBatch,
    ) : SymbolDiscoveryOutcome

    data class Qualified(
        val batch: SymbolDiscoveryBatch,
        val qualifications: SymbolDiscoveryQualifications,
    ) : SymbolDiscoveryOutcome
}
