package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedRevalidationCountDiagnosticsTest {
    @Test
    fun `unentered completed receipt proves both revalidation counter capabilities with explicit zeros`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        val receipt = receipts.single()
        assertEquals(HostedSemanticEntry.NotEntered, receipt.semanticEntry)
        assertEquals(HostedNativePhaseState.NotEntered, receipt.nativePhase)
        assertEquals(HostedDiagnosticOutcome.Completed, receipt.outcome)
        assertCounts(receipt, retained = 0L, rejected = 0L)
    }

    @Test
    fun `retained and rejected revalidation counts accumulate independently`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostic.count(IntellijReadCounter.REVALIDATION_LOCATORS_RETAINED, amount = 2)
        diagnostic.count(IntellijReadCounter.REVALIDATION_LOCATORS_REJECTED, amount = 3)
        diagnostic.count(IntellijReadCounter.REVALIDATION_LOCATORS_RETAINED, amount = 2)
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        assertCounts(receipts.single(), retained = 4L, rejected = 3L)
    }

    @Test
    fun `cancellation and rejected completion preserve observed revalidation counts without later mutation`() {
        for (failure in listOf(HostedQueryFailure.CANCELLED, HostedQueryFailure.CONTENT_MOVED)) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
            diagnostic.count(IntellijReadCounter.REVALIDATION_LOCATORS_RETAINED, amount = 2)
            diagnostic.count(IntellijReadCounter.REVALIDATION_LOCATORS_REJECTED)
            val outcome = HostedDiagnosticOutcome.Rejected(failure)
            diagnostic.finish(outcome)
            diagnostic.count(IntellijReadCounter.REVALIDATION_LOCATORS_RETAINED)
            diagnostic.count(IntellijReadCounter.REVALIDATION_LOCATORS_REJECTED)
            diagnostic.finish(HostedDiagnosticOutcome.Completed)
            val receipt = receipts.single()
            assertEquals(outcome, receipt.outcome)
            assertCounts(receipt, retained = 2L, rejected = 1L)
        }
    }

    @Test
    fun `unentered preemption capability encodes zero and cancellation preserves actual increments`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val unentered = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        unentered.finish(HostedDiagnosticOutcome.Completed)
        val cancelled = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        cancelled.count(IntellijReadCounter.EPOCH_READ_PREEMPTIONS, amount = 2)
        cancelled.count(IntellijReadCounter.EPOCH_READ_PREEMPTIONS, amount = 3)
        val cancelledOutcome = HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED)
        cancelled.finish(cancelledOutcome)
        cancelled.count(IntellijReadCounter.EPOCH_READ_PREEMPTIONS)
        cancelled.finish(HostedDiagnosticOutcome.Completed)
        assertEquals(2, receipts.size)
        assertEquals(HostedDiagnosticOutcome.Completed, receipts[0].outcome)
        assertEquals(cancelledOutcome, receipts[1].outcome)
        for ((receipt, expectedCount) in listOf(receipts[0] to 0L, receipts[1] to 5L)) {
            assertEquals(HostedSemanticEntry.NotEntered, receipt.semanticEntry)
            assertEquals(HostedNativePhaseState.NotEntered, receipt.nativePhase)
            val encoded =
                Json.parseToJsonElement(receipt.encode()).jsonObject.getValue("counters").jsonArray.filter {
                    it.jsonObject.getValue("counter").jsonPrimitive.content == "EPOCH_READ_PREEMPTIONS"
                }
            assertEquals(1, encoded.size)
            val projected = encoded.single().jsonObject
            assertEquals(setOf("counter", "contributor", "count"), projected.keys)
            assertEquals(JsonPrimitive("EPOCH_READ_PREEMPTIONS"), projected.getValue("counter"))
            assertEquals(JsonPrimitive("NONE"), projected.getValue("contributor"))
            assertEquals(JsonPrimitive(expectedCount), projected.getValue("count"))
            assertEquals(
                listOf(
                    HostedNativeCount(
                        IntellijReadCounter.EPOCH_READ_PREEMPTIONS,
                        IntellijReadContributor.NONE,
                        expectedCount,
                    )
                ),
                receipt.counters.filter { it.counter == IntellijReadCounter.EPOCH_READ_PREEMPTIONS },
            )
        }
    }

    private fun assertCounts(receipt: HostedReadDiagnosticReceipt, retained: Long, rejected: Long) {
        val expected =
            listOf(
                HostedNativeCount(
                    IntellijReadCounter.REVALIDATION_LOCATORS_RETAINED,
                    IntellijReadContributor.NONE,
                    retained,
                ),
                HostedNativeCount(
                    IntellijReadCounter.REVALIDATION_LOCATORS_REJECTED,
                    IntellijReadContributor.NONE,
                    rejected,
                ),
            )
        assertEquals(expected, receipt.counters.filter { it.counter in expected.map(HostedNativeCount::counter) })
        val encoded =
            Json.parseToJsonElement(receipt.encode()).jsonObject.getValue("counters").jsonArray.filter {
                it.jsonObject.getValue("counter").jsonPrimitive.content in
                    setOf("REVALIDATION_LOCATORS_RETAINED", "REVALIDATION_LOCATORS_REJECTED")
            }
        assertEquals(2, encoded.size)
        for ((index, count) in expected.withIndex()) {
            val projected = encoded[index].jsonObject
            assertEquals(setOf("counter", "contributor", "count"), projected.keys)
            assertEquals(JsonPrimitive(count.counter.name), projected.getValue("counter"))
            assertEquals(JsonPrimitive("NONE"), projected.getValue("contributor"))
            assertEquals(JsonPrimitive(count.count), projected.getValue("count"))
        }
    }
}
