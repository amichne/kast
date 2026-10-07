package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CallbackReadmission
import io.github.amichne.kast.relation.contract.CallbackSummaryCacheLookup
import io.github.amichne.kast.relation.contract.CallbackSummaryCachePort
import io.github.amichne.kast.relation.contract.CallbackSupplierCacheLookup
import io.github.amichne.kast.relation.contract.CallbackSupplierCachePort
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierPartition
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached fact admission and accounting, without claiming native compiler resolution. */
class CallbackParameterSummariesTest {
    private val fixture = RelationReadTest()
    private val request = fixture.request(RelationMeaning.Callees)

    @Test
    fun `lookup reuses admitted exact formal without charging duplicate storage`() {
        val summary = summary()
        val summaries =
            CallbackParameterSummaries(
                RelationBudget(
                    request.budget.resources,
                    RelationByteLimit.parse(summary.retainedBytes).refined(),
                )
            )
        assertNull(summaries.find(summary.formal))
        assertTrue(summaries.retain(summary) is Refinement.Refined)
        assertSame(summary, summaries.find(summary.formal))
        assertTrue(summaries.retain(summary) is Refinement.Refined)
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), summaries.retention.admit(1L))
    }

    @Test
    fun `cache storage and scan facts share admission and failed cache entry stays absent`() {
        val summary = summary()
        val summaries =
            CallbackParameterSummaries(
                RelationBudget(
                    request.budget.resources,
                    RelationByteLimit.parse(summary.retainedBytes).refined(),
                )
            )
        assertTrue(summaries.retention.admit(1L) is Refinement.Refined)
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), summaries.retain(summary))
        assertNull(summaries.find(summary.formal))
    }

    @Test
    fun `another exact formal does not alias a cached summary`() {
        val summary = summary()
        val summaries = CallbackParameterSummaries(request.budget)
        assertTrue(summaries.retain(summary) is Refinement.Refined)
        val target = fixture.fact(request, identity = "another").target
        val formal =
            CallbackParameterIdentity.fromCompiler(
                    target,
                    ValueArgumentPosition.parse(0).refined(),
                    RelationOccurrence.fromBoundary(target.file, 72, 73).refined(),
                )
                .refined()
        assertNull(summaries.find(formal))
    }

    @Test
    fun `project lookup is counted only after successful supplier use and once per partition`() {
        val summary = summary()
        val cache = ObservedCache(summary)
        val summaries = CallbackParameterSummaries(request.budget, cache = cache)
        assertSame(summary, summaries.find(summary.formal))
        assertEquals(0, cache.admissions)
        summaries.used(summary)
        assertEquals(1, cache.admissions)
        summaries.used(summary)
        assertEquals(1, cache.admissions)
        assertEquals(1, cache.lookups)
    }

    @Test
    fun `project hit rejected by request retention never counts as reused`() {
        val summary = summary()
        val cache = ObservedCache(summary)
        val summaries =
            CallbackParameterSummaries(
                RelationBudget(request.budget.resources, RelationByteLimit.parse(1L).refined()),
                cache = cache,
            )
        assertNull(summaries.find(summary.formal))
        summaries.used(summary)
        assertEquals(0, cache.admissions)
        assertEquals(1, cache.lookups)
    }

    @Test
    fun `rejected current endpoint restoration leaves fresh extraction available`() {
        val summary = summary()
        var restorations = 0
        val cache =
            object : CallbackSummaryCachePort {
                override fun find(
                    formal: CallbackParameterIdentity,
                    readmit: (CallbackParameterSummary) -> CallbackReadmission<CallbackParameterSummary>,
                ): CallbackSummaryCacheLookup {
                    restorations++
                    assertTrue(readmit(summary) is Refinement.Rejected)
                    return CallbackSummaryCacheLookup.Miss
                }

                override fun retain(summary: CallbackParameterSummary) = Unit

                override fun admitted(summary: CallbackParameterSummary): Unit =
                    error("Rejected restoration cannot count as reused")
            }
        val summaries = CallbackParameterSummaries(request.budget, cache = cache)
        assertNull(summaries.find(summary.formal))
        assertTrue(summaries.retain(summary) is Refinement.Refined)
        assertSame(summary, summaries.find(summary.formal))
        assertEquals(1, restorations)
    }

    @Test
    fun `supplier project hit shares request byte admission and counts only consumed inventory once`() {
        val inventory = suppliers()
        val cache = ObservedSupplierCache(inventory)
        val summaries =
            CallbackParameterSummaries(
                RelationBudget(request.budget.resources, RelationByteLimit.parse(inventory.retainedBytes).refined()),
                cache = cache,
            )
        assertSame(inventory, summaries.findSupplierInventory(inventory.root, inventory.domain))
        assertEquals(0, cache.admissions)
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), summaries.retention.admit(1L))
        summaries.supplierUsed(inventory)
        summaries.supplierUsed(inventory)
        assertSame(inventory, summaries.findSupplierInventory(inventory.root, inventory.domain))
        assertEquals(1, cache.admissions)
        assertEquals(1, cache.lookups)
    }

    @Test
    fun `supplier project hit rejected by request retention never counts as consumed`() {
        val inventory = suppliers()
        val cache = ObservedSupplierCache(inventory)
        val summaries =
            CallbackParameterSummaries(
                RelationBudget(
                    request.budget.resources,
                    RelationByteLimit.parse(inventory.retainedBytes - 1).refined(),
                ),
                cache = cache,
            )
        assertNull(summaries.findSupplierInventory(inventory.root, inventory.domain))
        summaries.supplierUsed(inventory)
        assertEquals(0, cache.admissions)
        assertEquals(1, cache.lookups)
    }

    private fun suppliers(): CompleteCallbackSupplierInventory {
        val formal = summary().formal
        val partition =
            CompleteCallbackSupplierPartition.fromCompiler(
                    formal,
                    emptyList(),
                    emptyList(),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .refined()
        return CompleteCallbackSupplierInventory.fromCompiler(
                formal,
                RelationScopeFingerprint.from(formal.callable),
                listOf(partition),
            )
            .refined()
    }

    private class ObservedSupplierCache(private val inventory: CompleteCallbackSupplierInventory) :
        CallbackSummaryCachePort {
        var lookups = 0
        var admissions = 0
        override val suppliers: CallbackSupplierCachePort =
            object : CallbackSupplierCachePort {
                override fun find(
                    root: CallbackParameterIdentity,
                    domain: RelationScopeFingerprint,
                    readmit:
                        (CompleteCallbackSupplierInventory) -> CallbackReadmission<CompleteCallbackSupplierInventory>,
                ): CallbackSupplierCacheLookup {
                    assertEquals(inventory.root, root)
                    assertEquals(inventory.domain, domain)
                    lookups++
                    return CallbackSupplierCacheLookup.Found(inventory)
                }

                override fun retain(inventory: CompleteCallbackSupplierInventory): Unit =
                    error("Unexpected fresh supplier publication")

                override fun admitted(inventory: CompleteCallbackSupplierInventory) {
                    assertSame(this@ObservedSupplierCache.inventory, inventory)
                    admissions++
                }
            }

        override fun find(
            formal: CallbackParameterIdentity,
            readmit: (CallbackParameterSummary) -> CallbackReadmission<CallbackParameterSummary>,
        ): CallbackSummaryCacheLookup = error("Unexpected summary lookup")

        override fun retain(summary: CallbackParameterSummary): Unit = error("Unexpected summary publication")

        override fun admitted(summary: CallbackParameterSummary): Unit = error("Unexpected summary use")
    }

    private class ObservedCache(private val summary: CallbackParameterSummary) : CallbackSummaryCachePort {
        var lookups = 0
        var admissions = 0

        override fun find(
            formal: CallbackParameterIdentity,
            readmit: (CallbackParameterSummary) -> CallbackReadmission<CallbackParameterSummary>,
        ): CallbackSummaryCacheLookup {
            assertEquals(summary.formal, formal)
            lookups++
            return CallbackSummaryCacheLookup.Found(summary)
        }

        override fun retain(summary: CallbackParameterSummary): Unit = error("Unexpected fresh publication")

        override fun admitted(summary: CallbackParameterSummary) {
            assertSame(this.summary, summary)
            admissions++
        }
    }

    private fun summary(): CallbackParameterSummary {
        val target = fixture.fact(request).target
        val formal =
            CallbackParameterIdentity.fromCompiler(
                    target,
                    ValueArgumentPosition.parse(0).refined(),
                    RelationOccurrence.fromBoundary(target.file, 72, 73).refined(),
                )
                .refined()
        return CallbackParameterSummary.fromCompiler(
                formal,
                emptyList(),
                emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
            )
            .refined()
    }

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Unexpected rejection: $failure")
        }
}
