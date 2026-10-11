package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderRetainedGraph
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AutomaticDenseOwnerAccountingTest : AutomaticDenseRetentionCase() {
    @Test
    fun `retention observations distinguish zero capacity and saturated exhaustion`() {
        val allowance = QueryByteLimit.parse(100L).refined()
        val exact =
            QueryInvocationRetentionAdmission(
                QueryInvocationRetentionStage.BEFORE_EXECUTION,
                allowance,
                QueryRetentionByteCount.parse(70L).refined(),
                QueryRetentionByteCount.parse(20L).refined(),
                QueryRetentionByteCount.parse(10L).refined(),
            )
        assertEquals(100L, exact.total.value)
        assertEquals(0L, (exact.capacity as QueryInvocationRetentionCapacity.Available).bytes.value)
        val exhausted =
            QueryInvocationRetentionAdmission(
                QueryInvocationRetentionStage.BEFORE_FACTS,
                allowance,
                QueryRetentionByteCount.parse(Long.MAX_VALUE).refined(),
                QueryRetentionByteCount.parse(20L).refined(),
                QueryRetentionByteCount.parse(10L).refined(),
            )
        assertEquals(Long.MAX_VALUE, exhausted.total.value)
        assertEquals(QueryInvocationRetentionCapacity.Exhausted, exhausted.capacity)
    }

    @Test
    fun `inventory owner ledger reconciles unique payloads reference cells and advancing state structure`() {
        val graph = RelationProviderRetainedGraph()
        var state = inventory
        var charged = state.retainedBytes(graph)
        repeat(20) {
            state = state.consume()
            charged += state.retainedBytes(graph)
        }
        val snapshot = graph.retentionLedger()
        val shared = snapshot.charges.single()
        assertEquals(1L, shared.owner.value)
        assertEquals(inventory.retainedBytes, shared.inventory.value)
        assertEquals(512L, shared.accountingMetadata.value)
        assertEquals(21L, shared.references.value)
        assertEquals(21L * 8L, shared.referenceCells.value)
        assertEquals(inventory.retainedBytes + 512L + 21L * 8L, snapshot.total.value)
        assertEquals(charged, snapshot.total.value + 21L * 512L)

        val detached = RelationProviderState.references(locators)
        charged += detached.retainedBytes(graph)
        val both = graph.retentionLedger()
        assertEquals(listOf(1L, 2L), both.charges.map { it.owner.value })
        assertEquals(listOf(21L, 1L), both.charges.map { it.references.value })
        assertEquals(List(2) { inventory.retainedBytes }, both.charges.map { it.inventory.value })
        assertEquals(charged, both.total.value + 22L * 512L)
        assertEquals(21L, snapshot.charges.single().references.value, "Earlier snapshots stay immutable")
    }

    @Test
    fun `inventory owner ledger commits only the accepted transaction`() {
        val graph = QueryImpactRetainedGraph()
        graph.providerState(inventory)
        val before = graph.providerRetentionLedger()
        val rejected = graph.transaction()
        rejected.graph.providerState(inventory.consume())
        rejected.graph.providerState(RelationProviderState.references(locators))
        assertEquals(2, rejected.graph.providerRetentionLedger().charges.size)
        assertEquals(before.total, graph.providerRetentionLedger().total)
        assertEquals(1L, graph.providerRetentionLedger().charges.single().references.value)

        val accepted = graph.transaction()
        accepted.graph.providerState(inventory.consume())
        accepted.commit()
        val after = graph.providerRetentionLedger()
        assertEquals(before.charges.single().owner, after.charges.single().owner)
        assertEquals(before.total.value + 8L, after.total.value)
        assertEquals(2L, after.charges.single().references.value)
        val next = graph.transaction()
        next.graph.providerState(RelationProviderState.references(locators))
        next.commit()
        assertEquals(listOf(1L, 2L), graph.providerRetentionLedger().charges.map { it.owner.value })
    }

    @Test
    fun `advancing inventory keeps exact state proof and reference charges`() {
        val graph = RelationProviderRetainedGraph()
        assertEquals(inventory.retainedBytes + 1_024L + 8L, inventory.retainedBytes(graph))
        var next = inventory
        repeat(20) {
            next = next.consume()
            assertEquals(512L + 8L, next.retainedBytes(graph))
        }
        val detached = RelationProviderState.references(locators)
        assertEquals(inventory.retainedBytes + 1_024L + 8L, detached.retainedBytes(graph))
        val result = read(RelationRequest.start(fixture.selector, RelationMeaning.References, fixture.budget))
        val confirmed = (result as RelationReadResult.Qualified).coverage as RelationIncompleteCoverage.Resumable
        val provider = confirmed.continuation.providerState
        val proofBytes = provider.retainedBytes - inventory.retainedBytes
        assertTrue(proofBytes > 0L)
        assertEquals(512L + 8L + proofBytes, provider.retainedBytes(graph))
    }
}
