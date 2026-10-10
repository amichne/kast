package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryCheckpointStorageAdmissionTest {
    @Test
    fun `estimate preserves each accounting owner and admits equality at the boundary`() {
        val estimate = QueryCheckpointStorageEstimate(bytes(11), bytes(13), bytes(17), bytes(19), bytes(23))
        assertEquals(83L, estimate.required.value)
        assertEquals(
            QueryCheckpointStorageOutcome.WITHIN_LIMIT,
            QueryCheckpointStorageAdmission.evaluate(estimate, QueryByteLimit.parse(83).refined()).outcome,
        )
        assertEquals(
            QueryCheckpointStorageOutcome.CAPACITY_EXCEEDED,
            QueryCheckpointStorageAdmission.evaluate(estimate, QueryByteLimit.parse(82).refined()).outcome,
        )
    }

    @Test
    fun `storage sums saturate and exceed the default checkpoint allowance`() {
        val estimate = QueryCheckpointStorageEstimate(bytes(Long.MAX_VALUE), bytes(1), bytes(0), bytes(0), bytes(0))
        assertEquals(Long.MAX_VALUE, estimate.required.value)
        assertEquals(
            QueryCheckpointStorageOutcome.CAPACITY_EXCEEDED,
            QueryCheckpointStorageAdmission.evaluate(estimate, QueryByteLimit.DefaultCheckpoint).outcome,
        )
    }

    @Test
    fun `negative storage is a finite rejection and empty storage remains valid`() {
        assertEquals(
            Refinement.Rejected(QueryCheckpointStorageFailure.NEGATIVE_BYTES),
            QueryCheckpointStorageBytes.parse(-1),
        )
        val empty = QueryCheckpointStorageEstimate(bytes(0), bytes(0), bytes(0), bytes(0), bytes(0))
        assertEquals(0L, empty.required.value)
        assertEquals(
            QueryCheckpointStorageOutcome.WITHIN_LIMIT,
            QueryCheckpointStorageAdmission.evaluate(empty, QueryByteLimit.parse(1).refined()).outcome,
        )
    }

    private fun bytes(value: Long) = QueryCheckpointStorageBytes.parse(value).refined()
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
