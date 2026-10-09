package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
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

    @Test
    fun `restoration success and failures encode additive bounded phase and outcome evidence`() {
        for (outcome in
            listOf(
                IntellijReadCounter.VALUE_SITE_SHAPES_RESTORED,
                IntellijReadCounter.VALUE_SITE_SHAPES_REJECTED,
                IntellijReadCounter.VALUE_SITE_ANCHORS_UNAVAILABLE,
            )) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostics = HostedReadDiagnostics({ 0L }, publish = receipts::add)
            diagnostics.phase(IntellijReadPhase.VALUE_SITE_RESTORATION)
            diagnostics.count(IntellijReadCounter.VALUE_SITE_RESTORATIONS)
            diagnostics.count(outcome)
            diagnostics.finish(HostedDiagnosticOutcome.Completed)
            val document = Json.parseToJsonElement(receipts.single().encode()).jsonObject
            assertEquals("9", document.getValue("schemaVersion").jsonPrimitive.content)
            val phase = document.getValue("nativePhase").jsonObject
            assertEquals(setOf("type", "phase"), phase.keys)
            assertEquals("entered", phase.getValue("type").jsonPrimitive.content)
            assertEquals("VALUE_SITE_RESTORATION", phase.getValue("phase").jsonPrimitive.content)
            val counters =
                document.getValue("counters").jsonArray.filter {
                    it.jsonObject.getValue("counter").jsonPrimitive.content.startsWith("VALUE_SITE_")
                }
            assertEquals(
                listOf("VALUE_SITE_RESTORATIONS", outcome.name),
                counters.map { it.jsonObject.getValue("counter").jsonPrimitive.content },
            )
            counters.forEach {
                val counter = it.jsonObject
                assertEquals(setOf("counter", "contributor", "count"), counter.keys)
                assertEquals("NONE", counter.getValue("contributor").jsonPrimitive.content)
                assertEquals("1", counter.getValue("count").jsonPrimitive.content)
            }
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
