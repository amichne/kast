package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedK2InvokeDiagnosticsTest {
    @Test
    fun `confirmed whole call keeps its own diagnostic stage`() {
        assertReason(IntellijReadTermination.K2_INVOKE_CALL_CONFIRMED, "K2_INVOKE_CALL_CONFIRMED")
    }

    @Test
    fun `unresolved whole call preserves rejection instead of confirmed receiver`() {
        assertReason(IntellijReadTermination.K2_INVOKE_CALL_UNRESOLVED, "K2_INVOKE_CALL_UNRESOLVED")
    }

    private fun assertReason(reason: IntellijReadTermination, expected: String) {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostics = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostics.terminated(reason)
        diagnostics.finish(HostedDiagnosticOutcome.Completed)
        val receipt = receipts.single()
        assertEquals(listOf(HostedNativeTermination(reason, IntellijReadContributor.NONE)), receipt.terminations)
        val encoded = Json.parseToJsonElement(receipt.encode()).jsonObject.getValue("terminations").jsonArray
        assertEquals(1, encoded.size)
        val termination = encoded.single().jsonObject
        assertEquals(setOf("reason", "contributor"), termination.keys)
        assertEquals(expected, termination.getValue("reason").jsonPrimitive.content)
        assertEquals("NONE", termination.getValue("contributor").jsonPrimitive.content)
    }
}
