package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedQueryRetentionDiagnosticsTest {
    @Test
    fun `closed retention counters serialize independently on successful and rejected read receipts`() {
        val expected =
            Json.parseToJsonElement(checkNotNull(javaClass.getResource("/query-retention-counters.json")).readText())
        for (outcome in
            listOf(HostedDiagnosticOutcome.Completed, HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED))) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
            diagnostic.phase(IntellijReadPhase.RETENTION)
            IntellijReadCounter.entries
                .filter { it.name.startsWith("QUERY_RETENTION_") }
                .forEach { diagnostic.count(it) }
            diagnostic.finish(outcome)
            val receipt = receipts.single()
            assertEquals(outcome, receipt.outcome)
            val encoded = Json.parseToJsonElement(receipt.encode()) as JsonObject
            val counters = encoded.getValue("counters") as JsonArray
            val retained = counters.filter {
                (it as JsonObject).getValue("counter").jsonPrimitive.content.startsWith("QUERY_RETENTION_")
            }
            assertEquals(expected.jsonArray.toList(), retained)
        }
    }
}
