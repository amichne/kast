package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.result.RelationCursorInvalidReason
import io.github.amichne.kast.api.contract.result.RelationCursorStaleReason
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.protocol.ConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RelationContinuationFailureTest {
    @Test
    fun `recognized continuation failures parse into typed evidence`() {
        val cases = mapOf(
            "generationChanged" to expected(
                RelationContinuationFailure.Stale(RelationCursorStaleReason.GENERATION_CHANGED),
                RelationshipSearchLimitation.GENERATION_CHANGED,
            ),
            "expired" to expected(
                RelationContinuationFailure.Stale(RelationCursorStaleReason.EXPIRED),
                RelationshipSearchLimitation.CONTINUATION_EXPIRED,
            ),
            "familyMismatch" to expected(
                RelationContinuationFailure.Invalid(RelationCursorInvalidReason.FAMILY_MISMATCH),
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
            "queryMismatch" to expected(
                RelationContinuationFailure.Invalid(RelationCursorInvalidReason.QUERY_MISMATCH),
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
            "unknown" to expected(
                RelationContinuationFailure.Invalid(RelationCursorInvalidReason.UNKNOWN_HANDLE),
                RelationshipSearchLimitation.CONTINUATION_INVALID,
            ),
            "candidateBudgetReached" to expected(
                RelationContinuationFailure.Degraded(RelationContinuationDegradedReason.CANDIDATE_BUDGET_REACHED),
                RelationshipSearchLimitation.CANDIDATE_BUDGET_REACHED,
            ),
            "traversalStateBudgetReached" to expected(
                RelationContinuationFailure.Degraded(RelationContinuationDegradedReason.TRAVERSAL_STATE_BUDGET_REACHED),
                RelationshipSearchLimitation.TRAVERSAL_STATE_BUDGET_REACHED,
            ),
            "timeout" to expected(
                RelationContinuationFailure.Degraded(RelationContinuationDegradedReason.TIMEOUT),
                RelationshipSearchLimitation.TIMED_OUT,
            ),
        )

        cases.forEach { (wireValue, expected) ->
            val actual = conflict(wireValue).relationContinuationFailureOrNull()
            assertEquals(expected.failure, actual)
            assertEquals(expected.limitation, actual?.limitation)
        }
    }

    @Test
    fun `unrecognized continuation failures remain unhandled`() {
        assertNull(conflict("new-provider-failure").relationContinuationFailureOrNull())
    }

    private fun expected(
        failure: RelationContinuationFailure,
        limitation: RelationshipSearchLimitation,
    ) = ExpectedFailure(failure, limitation)

    private fun conflict(wireValue: String) = ConflictException(
        message = wireValue,
        details = mapOf("continuationFailure" to wireValue),
    )

    private data class ExpectedFailure(
        val failure: RelationContinuationFailure,
        val limitation: RelationshipSearchLimitation,
    )
}
