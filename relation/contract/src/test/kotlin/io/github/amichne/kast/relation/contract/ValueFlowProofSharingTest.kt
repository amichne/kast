package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueFlowProofSharingTest {
    @Test
    fun `checkpoint proof inventory is traversed once across new reads`() {
        val count = 1_024
        val endpoint = flowEndpoint("owner", 0, count * 10 + 100)
        fun step(index: Int): ValueFlowStep {
            val site =
                ValueSite.fromCompiler(endpoint, flowRange(index * 10 + 1, index * 10 + 2), ValueRole.LocalRead)
                    .flowRefined()
            val domain =
                RelationRequest.start(
                    endpoint,
                    RelationMeaning.References,
                    RelationBudget(
                        io.github.amichne.kast.kernel.ResourceBudget.RunnerControlled,
                        RelationByteLimit.parse(100_000).flowRefined(),
                        io.github.amichne.kast.kernel.ResultLimit.parse(1).flowRefined(),
                    ),
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                )
            return ValueFlowStep.fromCompiler(
                    site,
                    emptyList(),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    domain,
                    RelationWorkCount.parse(1).flowRefined(),
                )
                .flowRefined()
        }
        val retained = List(count, ::step)
        var visited = 0
        val previous =
            object : AbstractCollection<ValueFlowStep>() {
                override val size = retained.size

                override fun iterator(): Iterator<ValueFlowStep> = retained.asSequence().onEach { visited++ }.iterator()
            }
        val proofs = ValueFlowProofSharing(previous)
        repeat(count) { index ->
            val input = step(index)
            val shared = proofs.share(input)
            assertEquals(input.source.identity, shared.source.identity)
            assertSame(retained.first().source.executionOwner, shared.source.executionOwner)
            assertSame(input.domain, shared.domain)
            assertEquals(input.receipts, shared.receipts)
        }
        assertEquals(count, visited, "Prior proof visits must not grow with the number of new reads")
    }

    @Test
    fun `one input site keeps one output object and distinct equal inputs stay distinct`() {
        val endpoint = flowEndpoint("owner", 0, 200)
        val first = ValueSite.fromCompiler(endpoint, flowRange(10, 20), ValueRole.LocalRead).flowRefined()
        val second = ValueSite.fromCompiler(endpoint, flowRange(10, 20), ValueRole.LocalRead).flowRefined()
        val proofs = ValueFlowSiteSharing(ValueFlowProofSharing())
        val sharedFirst = proofs.site(first)
        val sharedSecond = proofs.site(second)
        assertEquals(first, second)
        assertSame(sharedFirst, proofs.site(first))
        assertSame(sharedSecond, proofs.site(second))
        assertNotSame(sharedFirst, sharedSecond)
    }

    @Test
    fun `equal full execution proofs share physical storage`() {
        val endpoint = flowEndpoint("owner", 0, 200)
        val first = ValueExecutionOwner.declaration(endpoint)
        val second = ValueExecutionOwner.declaration(endpoint)
        assertNotSame(first, second)
        val proofs = ValueFlowProofSharing(emptyList())
        assertSame(first, proofs.executionOwner(first))
        assertSame(first, proofs.executionOwner(second))
    }

    @Test
    fun `equal declaration identity cannot merge different admitted scopes`() {
        val endpoint = flowEndpoint("owner", 0, 200) as RelationEndpoint.Resolved
        val other =
            RelationEndpoint.resolve(
                    endpoint.lease,
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_ONLY,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                    endpoint.evidence,
                )
                .flowRefined()
        assertEquals(endpoint.valueIdentity, other.valueIdentity)
        assertNotEquals(endpoint.scope, other.scope)
        val first = ValueExecutionOwner.declaration(endpoint)
        val second = ValueExecutionOwner.declaration(other)
        assertEquals(first, second)
        val proofs = ValueFlowProofSharing(emptyList())
        assertSame(first, proofs.executionOwner(first))
        assertSame(second, proofs.executionOwner(second))
    }

    @Test
    fun `another semantic basis keeps its own execution proof`() {
        val local = ValueExecutionOwner.declaration(flowEndpoint("owner", 0, 200))
        val foreign = ValueExecutionOwner.declaration(flowEndpoint("owner", 0, 200, generation = 2))
        val proofs = ValueFlowProofSharing(emptyList())
        assertSame(local, proofs.executionOwner(local))
        assertSame(foreign, proofs.executionOwner(foreign))
    }
}
