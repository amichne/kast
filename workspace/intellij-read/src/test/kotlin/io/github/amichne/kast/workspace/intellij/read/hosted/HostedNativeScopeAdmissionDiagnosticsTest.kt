package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedNativeScopeAdmissionDiagnosticsTest {
    @Test
    fun `zero native scope admissions remain encoded on completion and rejection`() {
        for (outcome in
            listOf(
                HostedDiagnosticOutcome.Completed,
                HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.REJECTED),
            )) {
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
            diagnostic.finish(outcome)
            val counters = Json.parseToJsonElement(receipts.single().encode()).jsonObject.getValue("counters").jsonArray
            for (name in listOf("RELATION_SCOPE_ADMISSIONS_READY", "RELATION_SCOPE_ADMISSIONS_HALTED")) {
                val row = counters.single { it.jsonObject.getValue("counter").jsonPrimitive.content == name }.jsonObject
                assertEquals(setOf("counter", "contributor", "count"), row.keys)
                assertEquals("NONE", row.getValue("contributor").jsonPrimitive.content)
                assertEquals("0", row.getValue("count").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `ready and halted observations replace their zero baselines without duplicate rows`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostic = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostic.count(IntellijReadCounter.RELATION_SCOPE_ADMISSIONS_READY, amount = 2)
        diagnostic.count(IntellijReadCounter.RELATION_SCOPE_ADMISSIONS_HALTED)
        diagnostic.finish(HostedDiagnosticOutcome.Completed)
        val counters = Json.parseToJsonElement(receipts.single().encode()).jsonObject.getValue("counters").jsonArray
        val ready =
            counters
                .single { it.jsonObject.getValue("counter").jsonPrimitive.content == "RELATION_SCOPE_ADMISSIONS_READY" }
                .jsonObject
        val halted =
            counters
                .single {
                    it.jsonObject.getValue("counter").jsonPrimitive.content == "RELATION_SCOPE_ADMISSIONS_HALTED"
                }
                .jsonObject
        assertEquals("2", ready.getValue("count").jsonPrimitive.content)
        assertEquals("1", halted.getValue("count").jsonPrimitive.content)
    }
}
