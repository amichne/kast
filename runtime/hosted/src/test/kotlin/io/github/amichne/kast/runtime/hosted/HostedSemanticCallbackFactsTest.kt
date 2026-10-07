package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackSummaryCacheLookup
import io.github.amichne.kast.relation.contract.CallbackSummaryReadmissionFailure
import io.github.amichne.kast.relation.contract.CallbackSupplierCacheLookup
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSemanticCallbackFactsTest : HostedSemanticFactFixture() {
    @Test
    fun `failed compiler endpoint restoration never returns a stale cached fact`() {
        val prior = snapshot(owner.admit())
        val old = summary(prior.authority)
        HostedCallbackFactCache(prior, store, counts).retain(old)
        val current = snapshot(owner.advance())
        val cache = HostedCallbackFactCache(current, store, counts)
        var restores = 0
        assertEquals(
            CallbackSummaryCacheLookup.Miss,
            cache.find(summary(current.authority).formal) {
                restores++
                assertEquals(old, it)
                Refinement.Rejected(CallbackSummaryReadmissionFailure.WorkLimitReached)
            },
        )
        assertEquals(1, restores)
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS])
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED])
        val fresh = summary(current.authority)
        cache.retain(fresh)
        assertEquals(
            CallbackSummaryCacheLookup.Found(fresh),
            cache.find(fresh.formal) { error("Current fact needs no restoration") },
        )
    }

    @Test
    fun `changed source inventory rejects reuse before native restoration`() {
        val prior = snapshot(owner.admit())
        HostedCallbackFactCache(prior, store, counts).retain(summary(prior.authority))
        val current = snapshot(owner.advance(), 'b')
        val cache = HostedCallbackFactCache(current, store, counts)
        assertEquals(
            CallbackSummaryCacheLookup.Miss,
            cache.find(summary(current.authority).formal) { error("Changed inputs cannot restore old endpoints") },
        )
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_INVALIDATED])
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED])
    }

    @Test
    fun `fresh and current summary lookup preserve exact formal evidence and defer reuse observation`() {
        val current = snapshot(owner.admit())
        val fresh = summary(current.authority)
        val cache = HostedCallbackFactCache(current, store, counts)
        cache.retain(fresh)
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_EXTRACTED])
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_PUBLISHED])
        assertEquals(
            CallbackSummaryCacheLookup.Found(fresh),
            cache.find(fresh.formal) { error("Current fact needs no restoration") },
        )
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED])
        cache.admitted(fresh)
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED])
    }

    @Test
    fun `restoration that returns the prior authority is rejected for both fact families`() {
        val prior = snapshot(owner.admit())
        val oldSummary = summary(prior.authority)
        val oldInventory = suppliers(prior.authority)
        val priorCache = HostedCallbackFactCache(prior, store, counts)
        priorCache.retain(oldSummary)
        priorCache.suppliers.retain(oldInventory)
        val current = snapshot(owner.advance())
        val cache = HostedCallbackFactCache(current, store, counts)
        val formal = summary(current.authority).formal
        assertEquals(CallbackSummaryCacheLookup.Miss, cache.find(formal) { Refinement.Refined(oldSummary) })
        assertEquals(
            CallbackSupplierCacheLookup.Miss,
            cache.suppliers.find(formal, oldInventory.domain) { Refinement.Refined(oldInventory) },
        )
        assertEquals(2, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS])
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED])
    }
}
