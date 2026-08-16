package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets

enum class RelationMeasureFailure {
    NEGATIVE,
}

@JvmInline
value class RelationByteCount private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<RelationByteCount, RelationMeasureFailure>`.
         *
         * Establishes a non-negative canonical detached byte count. [RelationMeasureFailure] is
         * the closed expected failure. Raw counts may be extracted only by compiler collectors,
         * metrics, and transport.
         */
        fun parse(raw: Long): Refinement<RelationByteCount, RelationMeasureFailure> =
            if (raw >= 0L) Refinement.Refined(RelationByteCount(raw))
            else Refinement.Rejected(RelationMeasureFailure.NEGATIVE)
    }
}

@JvmInline
value class RelationWorkCount private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<RelationWorkCount, RelationMeasureFailure>`.
         *
         * Establishes a non-negative number of native items examined. [RelationMeasureFailure] is
         * the closed expected failure. Raw counts may be extracted only by compiler collectors,
         * metrics, and continuation issuance.
         */
        fun parse(raw: Long): Refinement<RelationWorkCount, RelationMeasureFailure> =
            if (raw >= 0L) Refinement.Refined(RelationWorkCount(raw))
            else Refinement.Rejected(RelationMeasureFailure.NEGATIVE)
    }
}

enum class RelationBatchFailure {
    SUBJECT_MISMATCH,
    MEANING_MISMATCH,
    GENERATION_MISMATCH,
    NON_EXACT_FACT,
    RESULT_LIMIT_EXCEEDED,
    BYTE_LIMIT_EXCEEDED,
    WORK_LIMIT_EXCEEDED,
    NON_DETERMINISTIC_ORDER,
    ENCODED_BYTE_COUNT_MISMATCH,
}

@ConsistentCopyVisibility
data class RelationBatch private constructor(
    val request: RelationRequest,
    val facts: List<RelationFact>,
    val encodedBytes: RelationByteCount,
    val examinedWorkUnits: RelationWorkCount,
) {
    companion object {
        /**
         * Proof transition: `(RelationRequest, List<RelationFact>, RelationByteCount,
         * RelationWorkCount) -> Refinement<RelationBatch, RelationBatchFailure>`.
         *
         * Establishes exact request ownership, generation, meaning, individual edge coverage,
         * deterministic uniqueness, and request bounds for a detached one-hop page.
         * [RelationBatchFailure] is the closed expected failure. Raw collections and measures may
         * enter only at a bounded compiler collector or transport decoder.
         */
        fun create(
            request: RelationRequest,
            facts: List<RelationFact>,
            encodedBytes: RelationByteCount,
            examinedWorkUnits: RelationWorkCount,
        ): Refinement<RelationBatch, RelationBatchFailure> {
            if (facts.any { it.subject !== request.selector }) {
                return Refinement.Rejected(RelationBatchFailure.SUBJECT_MISMATCH)
            }
            if (facts.any { it.meaning != request.meaning }) {
                return Refinement.Rejected(RelationBatchFailure.MEANING_MISMATCH)
            }
            if (facts.any { it.generation != request.selector.lease.generation }) {
                return Refinement.Rejected(RelationBatchFailure.GENERATION_MISMATCH)
            }
            if (facts.any { it.coverage != RelationFactCoverage.EXACT_COMPILER_CONFIRMED }) {
                return Refinement.Rejected(RelationBatchFailure.NON_EXACT_FACT)
            }
            if (facts.size > request.budget.resources.resultLimit.value) {
                return Refinement.Rejected(RelationBatchFailure.RESULT_LIMIT_EXCEEDED)
            }
            if (encodedBytes.value > request.budget.returnedBytes.value) {
                return Refinement.Rejected(RelationBatchFailure.BYTE_LIMIT_EXCEEDED)
            }
            if (examinedWorkUnits.value > request.budget.resources.workUnitLimit.value) {
                return Refinement.Rejected(RelationBatchFailure.WORK_LIMIT_EXCEEDED)
            }
            if (facts != facts.distinct().sorted()) {
                return Refinement.Rejected(RelationBatchFailure.NON_DETERMINISTIC_ORDER)
            }
            val measured = facts.sumOf {
                it.canonicalProjection().toByteArray(StandardCharsets.UTF_8).size.toLong()
            }
            if (measured != encodedBytes.value) {
                return Refinement.Rejected(RelationBatchFailure.ENCODED_BYTE_COUNT_MISMATCH)
            }
            return Refinement.Refined(
                RelationBatch(request, facts.toList(), encodedBytes, examinedWorkUnits),
            )
        }
    }
}

enum class RelationLimitation {
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

@JvmInline
value class RelationExactCount internal constructor(val value: Int)

@JvmInline
value class RelationKnownMinimum internal constructor(val value: Int)

@ConsistentCopyVisibility
data class RelationCompleteCoverage internal constructor(
    val exactCount: RelationExactCount,
)

enum class RelationIncompleteCoverageFailure {
    EMPTY_LIMITATIONS,
    OFFSET_REWIND,
}

class RelationIncompleteCoverage private constructor(
    val knownMinimum: RelationKnownMinimum,
    val limitations: Set<RelationLimitation>,
    val continuation: RelationContinuation,
) {
    companion object {
        /**
         * Proof transition: `(RelationBatch, Set<RelationLimitation>, RelationWorkOffset) ->
         * Refinement<RelationIncompleteCoverage, RelationIncompleteCoverageFailure>`.
         *
         * Establishes non-empty incomplete-coverage reasons, a known-minimum count, and a
         * selector/meaning/generation-bound continuation that cannot move enumeration backward.
         * [RelationIncompleteCoverageFailure] is the closed expected failure. Raw limitations and
         * work positions may enter only from the bounded compiler collector.
         */
        fun create(
            batch: RelationBatch,
            limitations: Set<RelationLimitation>,
            nextWorkOffset: RelationWorkOffset,
        ): Refinement<RelationIncompleteCoverage, RelationIncompleteCoverageFailure> {
            if (limitations.isEmpty()) {
                return Refinement.Rejected(
                    RelationIncompleteCoverageFailure.EMPTY_LIMITATIONS,
                )
            }
            if (nextWorkOffset.value < batch.request.position.workOffset.value) {
                return Refinement.Rejected(RelationIncompleteCoverageFailure.OFFSET_REWIND)
            }
            return Refinement.Refined(
                RelationIncompleteCoverage(
                    knownMinimum = RelationKnownMinimum(batch.facts.size),
                    limitations = limitations.toSortedSet(compareBy { it.ordinal }).toSet(),
                    continuation = RelationContinuation.issue(batch.request, nextWorkOffset),
                ),
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RelationIncompleteCoverage &&
            knownMinimum == other.knownMinimum &&
            limitations == other.limitations &&
            continuation.fingerprint == other.continuation.fingerprint

    override fun hashCode(): Int =
        31 * (31 * knownMinimum.hashCode() + limitations.hashCode()) +
            continuation.fingerprint.hashCode()
}

enum class RelationCompilerRejection {
    WORKSPACE_ROOT_MISMATCH,
    GENERATION_MOVED,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    COMPILER_IDENTITY_UNAVAILABLE,
    COMPILER_CONTRACT_VIOLATION,
}

/** Closed output of the request-local compiler relation boundary. */
sealed interface RelationCompilation {
    @ConsistentCopyVisibility
    data class Complete internal constructor(
        val batch: RelationBatch,
        val coverage: RelationCompleteCoverage,
    ) : RelationCompilation

    @ConsistentCopyVisibility
    data class Qualified internal constructor(
        val batch: RelationBatch,
        val coverage: RelationIncompleteCoverage,
    ) : RelationCompilation

    data class Rejected(
        val reason: RelationCompilerRejection,
    ) : RelationCompilation

    companion object {
        /**
         * Proof transition: `RelationBatch + terminal compiler proof ->
         * RelationCompilation.Complete`.
         *
         * Establishes exact count and permits empty evidence to mean absence. Only a
         * limitation-free terminal compiler collector may call this transition.
         */
        fun complete(batch: RelationBatch): Complete = Complete(
            batch,
            RelationCompleteCoverage(RelationExactCount(batch.facts.size)),
        )

        /**
         * Proof transition: `(RelationBatch, Set<RelationLimitation>, RelationWorkOffset) ->
         * Refinement<RelationCompilation.Qualified, RelationIncompleteCoverageFailure>`.
         *
         * Establishes known-minimum evidence plus resumable incomplete coverage. Empty evidence
         * remains qualified and cannot represent absence. [RelationIncompleteCoverageFailure] is
         * the closed expected failure. Raw provider state may enter only from the bounded compiler
         * collector.
         */
        fun qualified(
            batch: RelationBatch,
            limitations: Set<RelationLimitation>,
            nextWorkOffset: RelationWorkOffset,
        ): Refinement<Qualified, RelationIncompleteCoverageFailure> = when (
            val coverage = RelationIncompleteCoverage.create(batch, limitations, nextWorkOffset)
        ) {
            is Refinement.Refined -> Refinement.Refined(Qualified(batch, coverage.value))
            is Refinement.Rejected -> coverage
        }
    }
}

/** Internal semantic effect port; implementations must return detached compiler evidence only. */
fun interface RelationCompilerPort {
    /**
     * Proof transition: `RelationRequest -> RelationCompilation`.
     *
     * A non-rejected result establishes exact one-hop compiler evidence and either exact terminal
     * or resumable incomplete coverage. [RelationCompilerRejection] is the closed expected
     * failure. Live compiler/platform values remain inside the implementation call.
     */
    suspend fun read(request: RelationRequest): RelationCompilation
}

enum class RelationReadRejection {
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    COMPILER_IDENTITY_UNAVAILABLE,
    COMPILER_CONTRACT_VIOLATION,
}

sealed interface RelationReadResult {
    data class Complete(
        val batch: RelationBatch,
        val coverage: RelationCompleteCoverage,
    ) : RelationReadResult

    data class Qualified(
        val batch: RelationBatch,
        val coverage: RelationIncompleteCoverage,
    ) : RelationReadResult

    data class Rejected(
        val reason: RelationReadRejection,
    ) : RelationReadResult
}

/** Public `relation.read` boundary. */
fun interface RelationOperations {
    /**
     * Proof transition: `RelationRequest -> RelationReadResult`.
     *
     * A complete or qualified result establishes current-generation, exact compiler-grounded
     * one-hop evidence. [RelationReadRejection] is the closed expected failure. Raw selector,
     * continuation, and budget inputs may enter only before [RelationRequest] construction.
     */
    suspend fun read(request: RelationRequest): RelationReadResult
}
