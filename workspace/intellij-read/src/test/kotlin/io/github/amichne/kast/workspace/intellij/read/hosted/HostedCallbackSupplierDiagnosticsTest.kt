package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedCallbackSupplierDiagnosticsTest {
    private val counters =
        listOf(
            IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_STARTED,
            IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_COMPLETED,
            IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_REJECTED,
            IntellijReadCounter.CALLBACK_SUPPLIER_CALLS_EXAMINED,
            IntellijReadCounter.CALLBACK_SUPPLIER_VALUES_CONFIRMED,
        )

    @Test
    fun `unentered supplier partitions encode explicit zeros`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        HostedReadDiagnostics({ 0L }, publish = receipts::add).finish(HostedDiagnosticOutcome.Completed)
        assertCounts(receipts.single(), List(5) { 0L })
    }

    @Test
    fun `failed supplier partition preserves work and success counters independently`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostics = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostics.count(IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_STARTED)
        diagnostics.count(IntellijReadCounter.CALLBACK_SUPPLIER_CALLS_EXAMINED, amount = 3)
        diagnostics.count(IntellijReadCounter.CALLBACK_SUPPLIER_VALUES_CONFIRMED)
        diagnostics.count(IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_REJECTED)
        diagnostics.finish(HostedDiagnosticOutcome.Completed)
        diagnostics.count(IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_COMPLETED)
        assertCounts(receipts.single(), listOf(1L, 0L, 1L, 3L, 1L))
    }

    private fun assertCounts(receipt: HostedReadDiagnosticReceipt, expected: List<Long>) {
        assertEquals(
            counters.mapIndexed { index, counter ->
                HostedNativeCount(counter, IntellijReadContributor.NONE, expected[index])
            },
            receipt.counters.filter { it.counter in counters },
        )
        val encoded =
            Json.parseToJsonElement(receipt.encode())
                .jsonObject
                .getValue("counters")
                .jsonArray
                .map { it.jsonObject }
                .filter { value -> counters.any { it.name == value.getValue("counter").jsonPrimitive.content } }
        assertEquals(5, encoded.size)
        encoded.forEachIndexed { index, value ->
            assertEquals(setOf("counter", "contributor", "count"), value.keys)
            assertEquals(counters[index].name, value.getValue("counter").jsonPrimitive.content)
            assertEquals("NONE", value.getValue("contributor").jsonPrimitive.content)
            assertEquals(expected[index].toString(), value.getValue("count").jsonPrimitive.content)
        }
    }
}
