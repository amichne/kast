package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AutomaticPrivateReplayTest : AutomaticSymbolQueryCase() {
    @Test
    fun `automatic completion characterizes private replay owners after three pages`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val script = Script(listOf(listOf(row), emptyList(), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store)
        val result = protocol.execute(request, fixture.authority, budget, policy()) as OperationOutcome.Complete
        assertEquals(QueryInvocationStop.COMPLETED, result.evidence.payload.invocation!!.stop)
        assertEquals(2, result.evidence.payload.items.values.size)
        assertEquals(result.evidence.payload.items.values.first(), result.evidence.payload.items.values.last())
        val retained = store.retentionMeasurements()
        assertEquals(2, retained.retainedEntries.value)
        println(
            "PrivateReplayBaseline(entries=${retained.retainedEntries.value}, " +
                "bytes=${retained.retainedBytes.value}, calls=${script.calls})"
        )
        script.assertDrained()
    }
}
