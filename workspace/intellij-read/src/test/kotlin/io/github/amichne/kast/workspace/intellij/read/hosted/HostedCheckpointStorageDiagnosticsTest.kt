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

class HostedCheckpointStorageDiagnosticsTest {
    @Test
    fun `encoded checkpoint rejection remains visible after a later successful admission`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostic.measure(IntellijReadGauge.QUERY_CHECKPOINT_ALLOWANCE, value(80))
        diagnostic.measure(IntellijReadGauge.QUERY_CHECKPOINT_REQUIRED_BYTES, value(83))
        diagnostic.measure(IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_ALLOWANCE, value(80))
        diagnostic.measure(IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_REQUIRED_BYTES, value(83))
        diagnostic.measure(IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_TASK_BYTES, value(11))
        diagnostic.count(IntellijReadCounter.QUERY_CHECKPOINT_CAPACITY_EXCEEDED)
        diagnostic.measure(IntellijReadGauge.QUERY_CHECKPOINT_ALLOWANCE, value(90))
        diagnostic.measure(IntellijReadGauge.QUERY_CHECKPOINT_REQUIRED_BYTES, value(20))
        diagnostic.count(IntellijReadCounter.QUERY_CHECKPOINT_WITHIN_LIMIT)
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        val encoded = Json.parseToJsonElement(receipts.single().encode()) as JsonObject
        assertEquals(JsonPrimitive(13), encoded.getValue("schemaVersion"))
        val expected =
            listOf(
                "QUERY_CHECKPOINT_ALLOWANCE" to 90L,
                "QUERY_CHECKPOINT_REQUIRED_BYTES" to 20L,
                "QUERY_CHECKPOINT_REJECTION_ALLOWANCE" to 80L,
                "QUERY_CHECKPOINT_REJECTION_REQUIRED_BYTES" to 83L,
                "QUERY_CHECKPOINT_REJECTION_TASK_BYTES" to 11L,
            )
        val gauges = encoded.getValue("gauges") as JsonArray
        assertEquals(expected.size, gauges.size)
        for ((index, entry) in expected.withIndex()) {
            val gauge = gauges[index] as JsonObject
            assertEquals(setOf("gauge", "value"), gauge.keys)
            assertEquals(JsonPrimitive(entry.first), gauge.getValue("gauge"))
            assertEquals(JsonPrimitive(entry.second), gauge.getValue("value"))
        }
        assertCheckpointCounters(encoded)
    }

    private fun assertCheckpointCounters(encoded: JsonObject) {
        val counters =
            (encoded.getValue("counters") as JsonArray).filter {
                (it as JsonObject).getValue("counter") in
                    setOf(
                        JsonPrimitive("QUERY_CHECKPOINT_WITHIN_LIMIT"),
                        JsonPrimitive("QUERY_CHECKPOINT_CAPACITY_EXCEEDED"),
                    )
            }
        assertEquals(2, counters.size)
        assertEquals(
            setOf(JsonPrimitive("QUERY_CHECKPOINT_WITHIN_LIMIT"), JsonPrimitive("QUERY_CHECKPOINT_CAPACITY_EXCEEDED")),
            counters.map { (it as JsonObject).getValue("counter") }.toSet(),
        )
        for (counter in counters) {
            val fields = counter as JsonObject
            assertEquals(setOf("counter", "contributor", "count"), fields.keys)
            assertEquals(JsonPrimitive("NONE"), fields.getValue("contributor"))
            assertEquals(JsonPrimitive(1), fields.getValue("count"))
        }
    }
}

private fun value(bytes: Long): IntellijReadGaugeValue =
    when (val parsed = IntellijReadGaugeValue.parse(bytes)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("Invalid fixture: ${parsed.failure}")
    }
