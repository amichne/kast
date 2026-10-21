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
    fun `one input site keeps one output object and distinct equal inputs stay distinct`() {
        val endpoint = flowEndpoint("owner", 0, 200)
        val first = ValueSite.fromCompiler(endpoint, flowRange(10, 20), ValueRole.LocalRead).flowRefined()
        val second = ValueSite.fromCompiler(endpoint, flowRange(10, 20), ValueRole.LocalRead).flowRefined()
        val proofs = ValueFlowProofSharing(emptyList())
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
