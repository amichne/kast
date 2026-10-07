package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSemanticFactDiagnosticsTest {
    private val counters =
        listOf(
            IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_EXTRACTED,
            IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED,
            IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_INVALIDATED,
            IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS,
            IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS,
            IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_PUBLISHED,
            IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED,
            IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_EXTRACTED,
            IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_REUSED,
            IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_INVALIDATED,
            IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_INELIGIBLE,
            IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_EXTRACTED,
            IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_REUSED,
            IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_INVALIDATED,
        )

    @Test
    fun `unentered semantic fact paths encode explicit zeros`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        HostedReadDiagnostics({ 0L }, publish = receipts::add).finish(HostedDiagnosticOutcome.Completed)
        assertCounts(receipts.single(), List(counters.size) { 0L })
    }

    @Test
    fun `rejected publication preserves successful revalidation and failed generation independently`() {
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val diagnostics = HostedReadDiagnostics({ 0L }, publish = receipts::add)
        diagnostics.count(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS, amount = 2)
        diagnostics.count(IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_INVALIDATED)
        diagnostics.count(IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED)
        diagnostics.finish(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED))
        diagnostics.count(IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_PUBLISHED)
        assertCounts(receipts.single(), listOf(0L, 0L, 1L, 2L, 0L, 0L, 1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L))
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
                .filter { it.getValue("counter").jsonPrimitive.content in counters.map { counter -> counter.name } }
        assertEquals(counters.size, encoded.size)
        encoded.forEachIndexed { index, value ->
            assertEquals(setOf("counter", "contributor", "count"), value.keys)
            assertEquals(counters[index].name, value.getValue("counter").jsonPrimitive.content)
            assertEquals("NONE", value.getValue("contributor").jsonPrimitive.content)
            assertEquals(expected[index].toString(), value.getValue("count").jsonPrimitive.content)
        }
    }
}
