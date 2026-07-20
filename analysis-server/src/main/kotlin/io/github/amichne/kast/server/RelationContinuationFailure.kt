package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.result.RelationCursorInvalidReason
import io.github.amichne.kast.api.contract.result.RelationCursorStaleReason
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.protocol.ConflictException

internal sealed interface RelationContinuationFailure {
    val limitation: RelationshipSearchLimitation

    data class Stale(
        val reason: RelationCursorStaleReason,
    ) : RelationContinuationFailure {
        override val limitation: RelationshipSearchLimitation = when (reason) {
            RelationCursorStaleReason.GENERATION_CHANGED -> RelationshipSearchLimitation.GENERATION_CHANGED
            RelationCursorStaleReason.EXPIRED -> RelationshipSearchLimitation.CONTINUATION_EXPIRED
        }
    }

    data class Invalid(
        val reason: RelationCursorInvalidReason,
    ) : RelationContinuationFailure {
        override val limitation: RelationshipSearchLimitation = RelationshipSearchLimitation.CONTINUATION_INVALID
    }

    data class Degraded(
        val reason: RelationContinuationDegradedReason,
    ) : RelationContinuationFailure {
        override val limitation: RelationshipSearchLimitation = when (reason) {
            RelationContinuationDegradedReason.CANDIDATE_BUDGET_REACHED ->
                RelationshipSearchLimitation.CANDIDATE_BUDGET_REACHED
            RelationContinuationDegradedReason.TRAVERSAL_STATE_BUDGET_REACHED ->
                RelationshipSearchLimitation.TRAVERSAL_STATE_BUDGET_REACHED
            RelationContinuationDegradedReason.TIMEOUT -> RelationshipSearchLimitation.TIMED_OUT
        }
    }
}

internal enum class RelationContinuationDegradedReason {
    CANDIDATE_BUDGET_REACHED,
    TRAVERSAL_STATE_BUDGET_REACHED,
    TIMEOUT,
}

internal fun ConflictException.relationContinuationFailureOrNull(): RelationContinuationFailure? =
    when (details["continuationFailure"]) {
        "generationChanged" -> RelationContinuationFailure.Stale(RelationCursorStaleReason.GENERATION_CHANGED)
        "expired" -> RelationContinuationFailure.Stale(RelationCursorStaleReason.EXPIRED)
        "familyMismatch" -> RelationContinuationFailure.Invalid(RelationCursorInvalidReason.FAMILY_MISMATCH)
        "queryMismatch" -> RelationContinuationFailure.Invalid(RelationCursorInvalidReason.QUERY_MISMATCH)
        "unknown" -> RelationContinuationFailure.Invalid(RelationCursorInvalidReason.UNKNOWN_HANDLE)
        "candidateBudgetReached" ->
            RelationContinuationFailure.Degraded(RelationContinuationDegradedReason.CANDIDATE_BUDGET_REACHED)
        "traversalStateBudgetReached" ->
            RelationContinuationFailure.Degraded(RelationContinuationDegradedReason.TRAVERSAL_STATE_BUDGET_REACHED)
        "timeout" -> RelationContinuationFailure.Degraded(RelationContinuationDegradedReason.TIMEOUT)
        else -> null
    }
