package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedCallbackProofDiagnosticsTest {
    @Test
    fun `encoded receipt preserves a coherent latest byte rejection across later ledger success`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_BYTE_ALLOWANCE, value(10))
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES, value(5))
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_REQUIRED_BYTES, value(11))
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_ALLOWANCE, value(10))
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_RETAINED_BYTES, value(5))
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_REQUIRED_BYTES, value(11))
        diagnostic.count(IntellijReadCounter.CALLBACK_PROOF_RETENTION_BYTE_REJECTED)
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_BYTE_ALLOWANCE, value(4))
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES, value(2))
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_REQUIRED_BYTES, value(2))
        diagnostic.count(IntellijReadCounter.CALLBACK_PROOF_RETENTION_ADMITTED)
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        diagnostic.measure(IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_REQUIRED_BYTES, value(1))
        val encoded = Json.parseToJsonElement(receipts.single().encode()) as JsonObject
        assertGauges(encoded)
        assertCounters(encoded)
    }

    private fun assertGauges(encoded: JsonObject) {
        val gauges = encoded.getValue("gauges") as JsonArray
        val expected =
            listOf(
                "CALLBACK_PROOF_BYTE_ALLOWANCE" to 4L,
                "CALLBACK_PROOF_RETAINED_BYTES" to 2L,
                "CALLBACK_PROOF_REQUIRED_BYTES" to 2L,
                "CALLBACK_PROOF_BYTE_REJECTION_ALLOWANCE" to 10L,
                "CALLBACK_PROOF_BYTE_REJECTION_RETAINED_BYTES" to 5L,
                "CALLBACK_PROOF_BYTE_REJECTION_REQUIRED_BYTES" to 11L,
            )
        assertEquals(expected.size, gauges.size)
        for ((index, entry) in expected.withIndex()) {
            val gauge = gauges[index] as JsonObject
            assertEquals(setOf("gauge", "value"), gauge.keys)
            assertEquals(JsonPrimitive(entry.first), gauge.getValue("gauge"))
            assertEquals(JsonPrimitive(entry.second), gauge.getValue("value"))
        }
    }

    private fun assertCounters(encoded: JsonObject) {
        val counters = encoded.getValue("counters") as JsonArray
        val relevant =
            counters
                .map { it as JsonObject }
                .filter {
                    (it.getValue("counter") as JsonPrimitive).content in
                        setOf(
                            "CALLBACK_PROOF_RETENTION_BYTE_REJECTED",
                            "CALLBACK_PROOF_RETENTION_ADMITTED",
                        )
                }
        assertEquals(2, relevant.size)
        for (counter in relevant) {
            assertEquals(setOf("counter", "contributor", "count"), counter.keys)
            assertEquals(JsonPrimitive("NONE"), counter.getValue("contributor"))
            assertEquals(JsonPrimitive(1), counter.getValue("count"))
        }
    }

    private fun value(raw: Long) = (IntellijReadGaugeValue.parse(raw) as Refinement.Refined).value
}
