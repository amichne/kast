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

class HostedRelationShapeDiagnosticsTest {
    @Test
    fun `inventory grant and unavailable outcome survive encoding on completion and rejection`() {
        for (outcome in
            listOf(HostedDiagnosticOutcome.Completed, HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED))) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
            diagnostic.measure(IntellijReadGauge.RELATION_ELAPSED_LIMIT_MILLIS, gaugeValue(1000L))
            diagnostic.measure(IntellijReadGauge.RELATION_ELAPSED_LIMIT_MILLIS, gaugeValue(700L))
            diagnostic.measure(IntellijReadGauge.RELATION_ELAPSED_BEFORE_PREPARATION_NANOS, gaugeValue(200_000_000L))
            diagnostic.count(IntellijReadCounter.RELATION_INVENTORY_UNAVAILABLE)
            diagnostic.finish(outcome)
            diagnostic.measure(IntellijReadGauge.RELATION_ELAPSED_LIMIT_MILLIS, gaugeValue(9999L))
            diagnostic.count(IntellijReadCounter.RELATION_INVENTORY_UNAVAILABLE)
            val receipt = receipts.single()
            assertEquals(outcome, receipt.outcome)
            val encoded = Json.parseToJsonElement(receipt.encode()) as JsonObject
            val gauges = encoded.getValue("gauges") as JsonArray
            assertEquals(
                listOf("RELATION_ELAPSED_LIMIT_MILLIS", "RELATION_ELAPSED_BEFORE_PREPARATION_NANOS")
                    .map(::JsonPrimitive),
                gauges.map { (it as JsonObject).getValue("gauge") },
            )
            assertEquals(
                listOf(700L, 200_000_000L).map(::JsonPrimitive),
                gauges.map { (it as JsonObject).getValue("value") },
            )
            gauges.forEach { assertEquals(setOf("gauge", "value"), (it as JsonObject).keys) }
            val unavailable =
                (encoded.getValue("counters") as JsonArray).single {
                    (it as JsonObject).getValue("counter") == JsonPrimitive("RELATION_INVENTORY_UNAVAILABLE")
                } as JsonObject
            assertEquals(setOf("counter", "contributor", "count"), unavailable.keys)
            assertEquals(JsonPrimitive("NONE"), unavailable.getValue("contributor"))
            assertEquals(JsonPrimitive(1L), unavailable.getValue("count"))
        }
    }

    private fun gaugeValue(value: Long): IntellijReadGaugeValue =
        when (val admitted = IntellijReadGaugeValue.parse(value)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Fixture gauge rejected: ${admitted.failure}")
        }

    @Test
    fun `relation reference shape counter names and counts survive typed diagnostic encoding`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostic.count(IntellijReadCounter.RELATION_REFERENCE_SHAPES_ADMITTED, amount = 2)
        diagnostic.count(IntellijReadCounter.RELATION_REFERENCE_SHAPES_SKIPPED, amount = 3)
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        val encoded = Json.parseToJsonElement(receipts.single().encode()) as JsonObject
        val counters =
            (encoded.getValue("counters") as JsonArray).filter {
                (it as JsonObject).getValue("counter") in
                    setOf(
                        JsonPrimitive("RELATION_REFERENCE_SHAPES_ADMITTED"),
                        JsonPrimitive("RELATION_REFERENCE_SHAPES_SKIPPED"),
                    )
            }
        assertEquals(2, counters.size)
        for ((index, expected) in
            listOf(
                    "RELATION_REFERENCE_SHAPES_ADMITTED" to 2L,
                    "RELATION_REFERENCE_SHAPES_SKIPPED" to 3L,
                )
                .withIndex()) {
            val counter = counters[index] as JsonObject
            assertEquals(setOf("counter", "contributor", "count"), counter.keys)
            assertEquals(JsonPrimitive(expected.first), counter.getValue("counter"))
            assertEquals(JsonPrimitive("NONE"), counter.getValue("contributor"))
            assertEquals(JsonPrimitive(expected.second), counter.getValue("count"))
        }
    }
}
