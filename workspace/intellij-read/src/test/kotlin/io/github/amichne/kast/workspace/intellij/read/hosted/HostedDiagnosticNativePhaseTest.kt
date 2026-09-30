package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedDiagnosticNativePhaseTest {
    @Test
    fun `diagnostic phase durations encode once after completion budget rejection or cancellation`() {
        val outcomes =
            listOf(
                HostedDiagnosticOutcome.Completed,
                HostedDiagnosticOutcome.Rejected(HostedQueryFailure.BUDGET_EXCEEDED),
                HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED),
            )
        for (outcome in outcomes) {
            var now = 0L
            val entries = mutableListOf<HostedNativePhaseEntry>()
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostics = HostedReadDiagnostics({ now }, publishPhase = entries::add, publish = receipts::add)
            diagnostics.phase(IntellijReadPhase.DIAGNOSTIC_SCOPE)
            now = 3L
            diagnostics.phase(IntellijReadPhase.DIAGNOSTIC_ENUMERATION)
            now = 11L
            diagnostics.phase(IntellijReadPhase.DIAGNOSTIC_ANALYSIS)
            now = 23L
            diagnostics.phase(IntellijReadPhase.DIAGNOSTIC_ANALYSIS)
            now = 41L
            diagnostics.phase(IntellijReadPhase.CANCELLATION_DRAINAGE)
            now = 50L
            diagnostics.finish(outcome)
            assertEquals(1, receipts.size)
            val receipt = receipts.single()
            val durations = receipt.nativePhaseDurations
            assertEquals(listOf(3L, 8L, 30L, 9L), durations.map { it.durationNanos })
            assertEquals(durations.map { it.phase }, entries.map { it.phase })
            assertEquals(outcome, receipt.outcome)
            val encoded =
                Json.parseToJsonElement(receipt.encode()).jsonObject.getValue("nativePhaseDurations").jsonArray
            assertEquals(
                listOf("DIAGNOSTIC_SCOPE", "DIAGNOSTIC_ENUMERATION", "DIAGNOSTIC_ANALYSIS", "CANCELLATION_DRAINAGE"),
                encoded.map { it.jsonObject.getValue("phase").jsonPrimitive.content },
            )
            assertEquals(
                listOf("3", "8", "30", "9"),
                encoded.map { it.jsonObject.getValue("durationNanos").jsonPrimitive.content },
            )
        }
    }
}
