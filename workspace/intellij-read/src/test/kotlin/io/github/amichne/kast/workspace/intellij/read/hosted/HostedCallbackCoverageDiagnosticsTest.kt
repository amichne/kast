package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedCallbackCoverageDiagnosticsTest {
    private val counters =
        listOf(
            IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_COMPLETE,
            IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_INCOMPLETE,
            IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_COMPLETE,
            IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE,
        )

    @Test
    fun `encoded stage counters distinguish unentered complete and incomplete coverage`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostics = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostics.count(IntellijReadCounter.CALLBACK_FORMAL_COVERAGE_COMPLETE)
        diagnostics.count(IntellijReadCounter.CALLBACK_SUPPLIER_COVERAGE_INCOMPLETE)
        diagnostics.finish(HostedDiagnosticOutcome.Completed)
        val encoded =
            Json.parseToJsonElement(receipts.single().encode())
                .jsonObject
                .getValue("counters")
                .jsonArray
                .map { it.jsonObject }
                .filter { value -> counters.any { it.name == value.getValue("counter").jsonPrimitive.content } }
        assertEquals(counters.map { it.name }, encoded.map { it.getValue("counter").jsonPrimitive.content })
        assertEquals(listOf("1", "0", "0", "1"), encoded.map { it.getValue("count").jsonPrimitive.content })
        encoded.forEach { assertEquals(setOf("counter", "contributor", "count"), it.keys) }
    }

    @Test
    fun `encoded integrated ownership and callback capabilities retain supported zeros`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostics = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostics.finish(HostedDiagnosticOutcome.Completed)
        val encoded =
            Json.parseToJsonElement(receipts.single().encode()).jsonObject.getValue("counters").jsonArray.map {
                it.jsonObject
            }
        val ownershipCounters = encoded.filter {
            it.getValue("counter").jsonPrimitive.content == IntellijReadCounter.RELATION_PATH_OWNERSHIP_PROBES.name
        }
        assertEquals(1, ownershipCounters.size)
        val ownershipCounter = ownershipCounters.single()
        assertEquals(setOf("counter", "contributor", "count"), ownershipCounter.keys)
        assertEquals("NONE", ownershipCounter.getValue("contributor").jsonPrimitive.content)
        assertEquals("0", ownershipCounter.getValue("count").jsonPrimitive.content)
        val callbackCounters = encoded.filter { value ->
            counters.any { it.name == value.getValue("counter").jsonPrimitive.content }
        }
        assertEquals(counters.map { it.name }, callbackCounters.map { it.getValue("counter").jsonPrimitive.content })
        assertEquals(listOf("0", "0", "0", "0"), callbackCounters.map { it.getValue("count").jsonPrimitive.content })
    }
}
