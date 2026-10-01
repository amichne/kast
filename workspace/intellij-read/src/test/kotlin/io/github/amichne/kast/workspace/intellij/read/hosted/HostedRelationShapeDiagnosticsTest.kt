package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedRelationShapeDiagnosticsTest {
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
