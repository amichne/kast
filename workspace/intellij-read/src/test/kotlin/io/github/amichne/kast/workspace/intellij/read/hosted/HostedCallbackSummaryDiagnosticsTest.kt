package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedCallbackSummaryDiagnosticsTest {
    private val counters =
        listOf(
            IntellijReadCounter.CALLBACK_BODY_SCANS,
            IntellijReadCounter.CALLBACK_BODY_SCANS_COMPLETED,
            IntellijReadCounter.CALLBACK_BODY_SCANS_INCOMPLETE,
            IntellijReadCounter.CALLBACK_SUMMARY_HITS,
            IntellijReadCounter.CALLBACK_SUMMARY_MISSES,
            IntellijReadCounter.CALLBACK_SUMMARY_REJECTIONS,
            IntellijReadCounter.CALLBACK_SUMMARIES_RETAINED,
            IntellijReadCounter.CALLBACK_SUMMARY_RETENTION_REJECTIONS,
            IntellijReadCounter.CALLBACK_FORWARDING_FORMALS,
            IntellijReadCounter.CALLBACK_FORWARDING_EDGES,
            IntellijReadCounter.CALLBACK_FIXED_POINTS_COMPLETED,
            IntellijReadCounter.CALLBACK_FIXED_POINTS_REJECTED,
        )

    @Test
    fun `unentered callback scans and summary alternatives encode explicit zeros`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        HostedReadDiagnostics({ 0L }, publish = receipts::add).finish(HostedDiagnosticOutcome.Completed)
        assertCounts(receipts.single(), counters.map { 0L })
    }

    @Test
    fun `cancelled read preserves incomplete scan and successful reuse independently`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostics = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostics.count(IntellijReadCounter.CALLBACK_BODY_SCANS, amount = 2)
        diagnostics.count(IntellijReadCounter.CALLBACK_BODY_SCANS_COMPLETED)
        diagnostics.count(IntellijReadCounter.CALLBACK_BODY_SCANS_INCOMPLETE)
        diagnostics.count(IntellijReadCounter.CALLBACK_SUMMARY_HITS)
        diagnostics.count(IntellijReadCounter.CALLBACK_FORWARDING_FORMALS, amount = 3)
        diagnostics.count(IntellijReadCounter.CALLBACK_FORWARDING_EDGES, amount = 4)
        diagnostics.count(IntellijReadCounter.CALLBACK_FIXED_POINTS_COMPLETED)
        diagnostics.count(IntellijReadCounter.CALLBACK_FIXED_POINTS_REJECTED)
        val outcome = HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED)
        diagnostics.finish(outcome)
        diagnostics.count(IntellijReadCounter.CALLBACK_BODY_SCANS_COMPLETED)
        assertEquals(outcome, receipts.single().outcome)
        assertCounts(receipts.single(), listOf(2L, 1L, 1L, 1L, 0L, 0L, 0L, 0L, 3L, 4L, 1L, 1L))
    }

    private fun assertCounts(receipt: HostedReadDiagnosticReceipt, expected: List<Long>) {
        assertEquals(
            counters.mapIndexed { index, counter ->
                HostedNativeCount(counter, IntellijReadContributor.NONE, expected[index])
            },
            receipt.counters.filter { it.counter in counters },
        )
        val encoded = Json.parseToJsonElement(receipt.encode()).jsonObject.getValue("counters").jsonArray
        val callbackCounts =
            encoded
                .map { it.jsonObject }
                .filter { it.getValue("counter").jsonPrimitive.content in counters.map { counter -> counter.name } }
        assertEquals(counters.size, callbackCounts.size)
        callbackCounts.forEachIndexed { index, count ->
            assertEquals(setOf("counter", "contributor", "count"), count.keys)
            assertEquals(counters[index].name, count.getValue("counter").jsonPrimitive.content)
            assertEquals("NONE", count.getValue("contributor").jsonPrimitive.content)
            assertEquals(expected[index].toString(), count.getValue("count").jsonPrimitive.content)
        }
    }
}
