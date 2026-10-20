package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedValueFlowCapacityDiagnosticsTest {
    @Test
    fun `encoded refusal preserves exact candidate reserve and allowance after completion`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostic.measure(IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_ALLOWANCE, value(93_307))
        diagnostic.measure(IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_CANDIDATE_BYTES, value(90_898))
        diagnostic.measure(IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_OBLIGATION_RESERVE_BYTES, value(43_008))
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        diagnostic.measure(IntellijReadGauge.VALUE_FLOW_BYTE_REJECTION_ALLOWANCE, value(1))

        val encoded = Json.parseToJsonElement(receipts.single().encode()) as JsonObject
        val gauges = encoded.getValue("gauges") as JsonArray
        val expected =
            listOf(
                "VALUE_FLOW_BYTE_REJECTION_ALLOWANCE" to 93_307L,
                "VALUE_FLOW_BYTE_REJECTION_CANDIDATE_BYTES" to 90_898L,
                "VALUE_FLOW_BYTE_REJECTION_OBLIGATION_RESERVE_BYTES" to 43_008L,
            )
        assertEquals(expected.size, gauges.size)
        for ((index, entry) in expected.withIndex()) {
            val gauge = gauges[index] as JsonObject
            assertEquals(setOf("gauge", "value"), gauge.keys)
            assertEquals(JsonPrimitive(entry.first), gauge.getValue("gauge"))
            assertEquals(JsonPrimitive(entry.second), gauge.getValue("value"))
        }
    }

    private fun value(raw: Long) = (IntellijReadGaugeValue.parse(raw) as Refinement.Refined).value
}
