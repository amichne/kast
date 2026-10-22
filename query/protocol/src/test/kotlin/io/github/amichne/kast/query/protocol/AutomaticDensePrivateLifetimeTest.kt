package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.query.contract.QueryByteLimit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AutomaticDensePrivateLifetimeTest : AutomaticDenseRetentionCase() {
    @Test
    fun `dense private replay release reduces issued owners under unchanged finite grants`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val admissions = mutableListOf<QueryInvocationRetentionAdmission>()
        val entries = mutableListOf<Int>()
        val base = policy(rows = 20, byteLimit = 32_768, retainedBytes = QueryByteLimit.DefaultCheckpoint.value)
        val observed =
            QueryInvocationPolicy(
                base.previewRows,
                base.previewBytesLimit,
                base.retainedBytes,
                base.previewBytes,
                nanoTime = { 0L },
                retentionObservation =
                    QueryInvocationRetentionObservation { admission ->
                        admissions += admission
                        entries += store.retentionMeasurements().retainedEntries.value
                    },
            )
        val protocol = CanonicalQueryProtocol(service, fixture.references, store)
        val rejection = completionRejection(protocol.execute(input, fixture.authority, grant, observed))
        val known = originalCoverage(rejection).knownMinimum.value
        assertEquals(QueryInvocationStop.TERMINAL_INCOMPLETE, rejection.stop)
        assertEquals(633, known, "The same finite grants rejected at 393 before private replay release")
        assertEquals(32, positions.size)
        assertTrue(entries.all { it <= 1 }, "Only the current private checkpoint stays in the store")
        assertEquals(1, store.retentionMeasurements().retainedEntries.value, "Only the final evidence result escapes")
        assertEquals((0 until positions.size).map { it * 20L }, positions)
        assertTrue(admissions.all { it.total.value == it.facts.value + it.issuedState.value + it.request.value })
        val before = admissions.last { it.stage == QueryInvocationRetentionStage.BEFORE_EXECUTION }
        val checkpoint = checkpointAdmissions.last()
        assertEquals(
            (before.allowance.bounded().limit.value - before.total.value) / 2L,
            checkpoint.allowance.bounded().limit.value,
        )
        assertTrue(checkpoint.estimate.required.value > checkpoint.allowance.bounded().limit.value)
        println(
            "DensePrivateLifetime(rows=$known, calls=${positions.size}, maxEntries=${entries.max()}, " +
                "facts=${before.facts.value}, issued=${before.issuedState.value}, request=${before.request.value}, " +
                "checkpointRequired=${checkpoint.estimate.required.value}, checkpointAllowance=${checkpoint.allowance.bounded().limit.value})"
        )
    }
}
