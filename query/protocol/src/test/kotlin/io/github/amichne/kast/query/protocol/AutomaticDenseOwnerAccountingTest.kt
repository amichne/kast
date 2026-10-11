package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.query.contract.QueryByteLimit
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
