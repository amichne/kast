package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedValueFlowDiagnosticsTest {
    private val providers =
        listOf(
            IntellijReadCounter.VALUE_PRODUCER_SEED_READS,
            IntellijReadCounter.VALUE_MODEL_REVALIDATIONS,
            IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS,
            IntellijReadCounter.VALUE_FLOW_READS,
        )

    @Test
    fun `unentered value providers encode explicit zeros`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        HostedReadDiagnostics({ 0L }, publish = receipts::add).finish(HostedDiagnosticOutcome.Completed)
        assertProviderCounts(receipts.single(), listOf(0L, 0L, 0L, 0L))
    }

    @Test
    fun `completed and rejected reads retain actual provider counts and forbid later mutation`() {
        for (outcome in
            listOf(HostedDiagnosticOutcome.Completed, HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED))) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostics = HostedReadDiagnostics({ 0L }, publish = receipts::add)
            diagnostics.count(IntellijReadCounter.VALUE_PRODUCER_SEED_READS, amount = 2)
            diagnostics.count(IntellijReadCounter.VALUE_FLOW_READS)
            diagnostics.finish(outcome)
            diagnostics.count(IntellijReadCounter.VALUE_FLOW_READS)
            diagnostics.finish(HostedDiagnosticOutcome.Completed)
            assertEquals(1, receipts.size)
            assertEquals(outcome, receipts.single().outcome)
            assertProviderCounts(receipts.single(), listOf(2L, 0L, 0L, 1L))
        }
    }

    private fun assertProviderCounts(receipt: HostedReadDiagnosticReceipt, expected: List<Long>) {
        assertEquals(
            providers.mapIndexed { index, counter ->
                HostedNativeCount(counter, IntellijReadContributor.NONE, expected[index])
            },
            receipt.counters.filter { it.counter in providers },
        )
        val encoded =
            Json.parseToJsonElement(receipt.encode()).jsonObject.getValue("counters").jsonArray.filter {
                it.jsonObject.getValue("counter").jsonPrimitive.content in providers.map(IntellijReadCounter::name)
            }
        assertEquals(4, encoded.size)
        for ((index, value) in encoded.withIndex()) {
            val count = value.jsonObject
            assertEquals(setOf("counter", "contributor", "count"), count.keys)
            assertEquals(providers[index].name, count.getValue("counter").jsonPrimitive.content)
            assertEquals("NONE", count.getValue("contributor").jsonPrimitive.content)
            assertEquals(expected[index].toString(), count.getValue("count").jsonPrimitive.content)
        }
    }
}
