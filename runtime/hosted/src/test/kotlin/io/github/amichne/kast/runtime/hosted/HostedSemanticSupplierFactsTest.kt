package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackSummaryReadmissionFailure
import io.github.amichne.kast.relation.contract.CallbackSupplierCacheLookup
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSemanticSupplierFactsTest : HostedSemanticFactFixture() {
    @Test
    fun `supplier inventory project hit is counted only after consumption`() {
        val current = snapshot(owner.admit())
        val cache = HostedCallbackFactCache(current, store, counts).suppliers
        val fresh = suppliers(current.authority)
        cache.retain(fresh)
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_EXTRACTED])
        assertEquals(
            CallbackSupplierCacheLookup.Found(fresh),
            cache.find(fresh.root, fresh.domain) { error("Current inventory needs no restoration") },
        )
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_REUSED])
        cache.admitted(fresh)
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_REUSED])
    }

    @Test
    fun `failed supplier endpoint restoration cannot expose the previous authority`() {
        val prior = snapshot(owner.admit())
        val old = suppliers(prior.authority)
        HostedCallbackFactCache(prior, store, counts).suppliers.retain(old)
        val current = snapshot(owner.advance())
        val cache = HostedCallbackFactCache(current, store, counts).suppliers
        val fresh = suppliers(current.authority)
        var restores = 0
        assertEquals(
            CallbackSupplierCacheLookup.Miss,
            cache.find(fresh.root, fresh.domain) {
                restores++
                assertEquals(old, it)
                Refinement.Rejected(CallbackSummaryReadmissionFailure.WorkLimitReached)
            },
        )
        assertEquals(1, restores)
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS])
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_REUSED])
        cache.retain(fresh)
        assertEquals(
            CallbackSupplierCacheLookup.Found(fresh),
            cache.find(fresh.root, fresh.domain) { error("Fresh current inventory needs no restoration") },
        )
    }

    @Test
    fun `changed supplier source rejects the entire closure before endpoint restoration`() {
        val prior = snapshot(owner.admit())
        HostedCallbackFactCache(prior, store, counts).suppliers.retain(suppliers(prior.authority))
        val current = snapshot(owner.advance(), 'b')
        val fresh = suppliers(current.authority)
        val cache = HostedCallbackFactCache(current, store, counts).suppliers
        assertEquals(
            CallbackSupplierCacheLookup.Miss,
            cache.find(fresh.root, fresh.domain) { error("Changed supplier universe cannot restore") },
        )
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_INVALIDATED])
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_REUSED])
    }
}
