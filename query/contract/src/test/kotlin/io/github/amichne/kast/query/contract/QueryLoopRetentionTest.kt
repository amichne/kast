package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueLoopIteration
import io.github.amichne.kast.relation.contract.ValueLoopProtocol
import io.github.amichne.kast.relation.contract.ValueLoopResultPhase
import io.github.amichne.kast.relation.contract.ValueLoopTransfer
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryLoopRetentionTest {
    @Test
    fun `requested loop result charges all operation selectors anchor and phase`() {
        val f = QueryImpactLedgerFixture()
        val protocol = protocol(f)
        val selectors =
            listOf(protocol.iterator, protocol.hasNext, protocol.next).map {
                SymbolSelector.issue(f.lease, f.scope, (it.callable as RelationEndpoint.Resolved).evidence)
            }
        val role =
            ValueSiteRoleClaim.LoopResult(
                f.producer.range,
                selectors[0],
                selectors[1],
                selectors[2],
                ValueLoopResultPhase.ITERATOR,
            )
        val request =
            ValueSiteRevalidationRequest.create(
                    SymbolSelector.issue(f.lease, f.scope, f.evidence),
                    f.producer.range,
                    role,
                    f.domain.budget,
                )
                .value()
        val site =
            ValueSite.fromCompiler(f.owner, request.anchor, ValueRole.LoopResult(protocol.iteratorResult)).value()
        val requested =
            QueryImpactRequestedSite.admit(
                    request,
                    RevalidatedValueSite.fromCompiler(request, site).value(),
                    RelationWorkCount.parse(1).value(),
                )
                .value()
        val graph = QueryImpactRetainedGraph()
        assertTrue(graph.requestedSite(requested) > QueryImpactRetainedGraph().site(site))
        for (selector in selectors) assertEquals(8L, graph.node(selector) { error("Selector was not retained") })
        assertEquals(8L, graph.node(role.phase) { error("Loop phase was not retained") })
        assertEquals(8L, graph.node(role.invocationAnchor) { error("Invocation anchor was not retained") })
    }

    @Test
    fun `retaining an iterator charges guard and next proofs while preserving object sharing`() {
        val f = QueryImpactLedgerFixture()
        val protocol = protocol(f)
        val site =
            ValueSite.fromCompiler(f.owner, f.producer.range, ValueRole.LoopResult(protocol.iteratorResult)).value()
        val graph = QueryImpactRetainedGraph()
        assertTrue(graph.site(site) > QueryImpactRetainedGraph().site(f.producer))
        for (call in listOf(protocol.iterator, protocol.hasNext, protocol.next)) {
            assertEquals(8L, call.storageBytes(graph))
            assertEquals(8L, call.callable.storageBytes(graph))
        }
        assertFalse(site.hasForeignBasis(f.lease))
        assertTrue(site.hasForeignBasis(QueryImpactLedgerFixture(generation = 2).lease))
    }

    @Test
    fun `advance retention charges normal true iteration once in addition to its shared protocol`() {
        val f = QueryImpactLedgerFixture()
        val protocol = protocol(f)
        val iteration = ValueLoopIteration.fromCompilerNormalTrue(protocol)
        val source =
            ValueSite.fromCompiler(f.owner, f.producer.range, ValueRole.LoopResult(protocol.iteratorResult)).value()
        val target =
            ValueSite.fromCompiler(
                    f.owner,
                    f.producer.range,
                    ValueRole.Receiver(protocol.next, ValueReceiverKind.DISPATCH),
                )
                .value()
        val proof = ValueLoopTransfer.Advance.fromCompiler(iteration, ValueReceiverKind.DISPATCH)
        val transfer = ValueTransfer.fromCompiler(source, target, ValueTransferKind.RECEIVER, proof).value()
        val graph = QueryImpactRetainedGraph()
        val first = graph.step(QueryImpactStep.Compiler(transfer))
        assertTrue(first > graph.step(QueryImpactStep.Compiler(transfer)))
        assertEquals(8L, iteration.storageBytes(graph))
        assertEquals(8L, protocol.storageBytes(graph))
        assertEquals(8L, protocol.hasNext.callable.storageBytes(graph))
    }

    private fun protocol(f: QueryImpactLedgerFixture): ValueLoopProtocol {
        fun call(name: String): ValueInvocation {
            val evidence =
                CompilerGroundedSymbolEvidence.fromBoundary(
                        f.file,
                        250,
                        280,
                        name,
                        "fixture.$name",
                        CompilerSymbolKind.FUNCTION,
                        CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), emptyList(), 0).value(),
                    )
                    .value()
            return ValueInvocation.fromCompiler(
                    f.owner,
                    f.producer.range,
                    RelationEndpoint.resolve(f.lease, f.scope, evidence).value(),
                )
                .value()
        }
        return ValueLoopProtocol.fromCompiler(call("iterator"), call("hasNext"), call("next")).value()
    }

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
