package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedTraversalPhaseDiagnosticsTest {
    @Test
    fun `traversal coordination intervals exclude native phases and retain one entry on completion or rejection`() {
        for (outcome in
            listOf(HostedDiagnosticOutcome.Completed, HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED))) {
            var now = 0L
            val entries = mutableListOf<HostedNativePhaseEntry>()
            val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
            val diagnostic = HostedReadDiagnostics({ now }, publishPhase = entries::add, publish = receipts::add)
            listOf(
                    0L to IntellijReadPhase.TRAVERSAL,
                    3L to IntellijReadPhase.REFERENCE_INVENTORY,
                    8L to IntellijReadPhase.REFERENCE_CONFIRMATION,
                    15L to IntellijReadPhase.TRAVERSAL,
                    19L to IntellijReadPhase.REFERENCE_INVENTORY,
                    21L to IntellijReadPhase.REFERENCE_CONFIRMATION,
                    24L to IntellijReadPhase.TRAVERSAL,
                    30L to IntellijReadPhase.ENCODING,
                )
                .forEach { (time, phase) ->
                    now = time
                    diagnostic.phase(phase)
                }
            now = 31L
            diagnostic.finish(outcome)
            val receipt = receipts.single()
            assertEquals(outcome, receipt.outcome)
            assertEquals(listOf(13L, 7L, 10L, 1L), receipt.nativePhaseDurations.map { it.durationNanos })
            assertEquals(
                listOf(
                    IntellijReadPhase.TRAVERSAL,
                    IntellijReadPhase.REFERENCE_INVENTORY,
                    IntellijReadPhase.REFERENCE_CONFIRMATION,
                    IntellijReadPhase.ENCODING,
                ),
                entries.map { it.phase },
            )
            assertEquals(listOf(0L, 3L, 8L, 30L), entries.map { it.enteredNanos })
            val encoded = Json.parseToJsonElement(receipt.encode()) as JsonObject
            val expected =
                Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/traversal-phase-durations.json")).readText()
                )
            assertEquals(expected, encoded.getValue("nativePhaseDurations"))
        }
    }
}
