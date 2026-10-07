package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackEndpointReadmissions
import io.github.amichne.kast.relation.contract.NamedRelationCacheLookup
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.readmitNamedRelations
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSemanticNamedFactsTest : HostedSemanticFactFixture() {
    @Test
    fun `named adjacency restoration yields the current request and counts only admitted use`() {
        val prior = snapshot(owner.admit())
        val old = named(prior.authority)
        HostedCallbackFactCache(prior, store, counts).namedRelations.retain(old)
        val current = snapshot(owner.advance())
        val request = named(current.authority).batch.request
        val cache = HostedCallbackFactCache(current, store, counts).namedRelations
        val found =
            cache.find(request) { partition ->
                val endpoints =
                    partition.endpoints.associateWith { endpoint ->
                        val oldEndpoint = endpoint as RelationEndpoint.Resolved
                        RelationEndpoint.resolve(
                                current.authority,
                                oldEndpoint.scope,
                                oldEndpoint.evidence,
                                oldEndpoint.constraints,
                            )
                            .value()
                    }
                CallbackEndpointReadmissions.fromCompiler(current.authority, endpoints)
                    .value()
                    .readmitNamedRelations(partition, request, RelationWorkCount.parse(1).value())
            } as NamedRelationCacheLookup.Found
        assertEquals(request, found.complete.batch.request)
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_REUSED])
        cache.admitted(found.complete)
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_REUSED])
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_EXTRACTED])
    }

    @Test
    fun `changed named inventory is rejected before restoration and stale result cannot escape`() {
        val prior = snapshot(owner.admit())
        val old = named(prior.authority)
        HostedCallbackFactCache(prior, store, counts).namedRelations.retain(old)
        val same = snapshot(owner.advance())
        assertEquals(
            NamedRelationCacheLookup.Miss,
            HostedCallbackFactCache(same, store, counts).namedRelations.find(named(same.authority).batch.request) {
                Refinement.Refined(old)
            },
        )
        val changed = snapshot(owner.advance(), 'b')
        assertEquals(
            NamedRelationCacheLookup.Miss,
            HostedCallbackFactCache(changed, store, counts).namedRelations.find(
                named(changed.authority).batch.request
            ) {
                error("Changed source cannot restore")
            },
        )
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_INVALIDATED])
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_REUSED])
    }
}
