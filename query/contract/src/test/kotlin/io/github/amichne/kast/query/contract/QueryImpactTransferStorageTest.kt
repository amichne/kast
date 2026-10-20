package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicateSubject
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.relation.contract.ValueTryBranchAlternative
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactTransferStorageTest {
    @Test
    fun `source evidence charges its wrapper and existing operation reference`() {
        val f = TransferStorageFixture()
        val graph = QueryImpactRetainedGraph()
        f.operation.storageBytes(graph)

        assertEquals(528L, f.evidence.storageBytes(graph))
        assertEquals(8L, f.evidence.storageBytes(graph))
        val distinct = ValueSourceTransfer.fromCompiler(f.operation)
        assertEquals(f.evidence, distinct)
        assertNotSame(f.evidence, distinct)
        assertEquals(528L, distinct.storageBytes(graph))
    }

    @Test
    fun `guard evidence charges its wrapper and both existing field references`() {
        val f = TransferStorageFixture()
        val predicates =
            ValuePredicates.fromCompiler(
                    listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(f.source)))
                )
                .value()
        val evidence = ValueTransferEvidence.Guarded.fromCompiler(f.evidence, predicates).value()
        val graph = QueryImpactRetainedGraph()
        f.evidence.storageBytes(graph)
        predicates.storageBytes(graph)

        assertEquals(536L, evidence.storageBytes(graph))
        assertEquals(8L, evidence.storageBytes(graph))
    }

    @Test
    fun `transfer shares the same evidence owner and keeps the public storage multiplier`() {
        val f = TransferStorageFixture()
        val graph = QueryImpactRetainedGraph()
        f.source.storageBytes(graph)
        f.target.storageBytes(graph)
        f.operation.storageBytes(graph)

        assertEquals(1064L, f.transfer.storageBytes(graph))
        assertEquals(8L, f.transfer.storageBytes(graph))
        assertEquals(4224L, graph.step(QueryImpactStep.Compiler(f.transfer)))
    }

    @Test
    fun `equal distinct operations and endpoint owners still pay their physical payload`() {
        val f = TransferStorageFixture()
        val graph = QueryImpactRetainedGraph()
        f.frame.storageBytes(graph)
        val distinct = ValueSourceOperation.SeedReturn(f.frame)
        assertEquals(f.operation, distinct)
        assertNotSame(f.operation, distinct)

        assertEquals(528L, f.operation.storageBytes(graph))
        assertEquals(8L, f.operation.storageBytes(graph))
        assertEquals(528L, distinct.storageBytes(graph))
        val endpoint = RelationEndpoint.resolve(f.base.lease, f.base.scope, f.base.evidence).value()
        assertEquals(f.base.owner, endpoint)
        assertNotSame(f.base.owner, endpoint)
        assertTrue(endpoint.storageBytes(graph) > 520L)
        assertEquals(8L, endpoint.valueExecutionOwner.storageBytes(graph))
    }

    @Test
    fun `normal branch evidence keeps its existing opaque allowance`() {
        val evidence =
            ValueTransferEvidence.NormalBranchResult.fromCompiler(
                    range(1, 190),
                    range(2, 180),
                    ValueTryBranchAlternative.TryBody,
                )
                .value()
        val graph = QueryImpactRetainedGraph()

        assertEquals(4616L, evidence.storageBytes(graph))
        assertEquals(8L, evidence.storageBytes(graph))
    }
}

private class TransferStorageFixture {
    val base = QueryImpactLedgerFixture()
    val frame =
        ValueCallFrame.direct(
                (base.producerWitness.origin as ValueProducerOrigin.Declared).invocation,
                base.owner.valueExecutionOwner,
            )
            .value()
    val operation = ValueSourceOperation.SeedReturn(frame)
    val evidence = ValueSourceTransfer.fromCompiler(operation)
    val source = ValueSite.fromCompiler(base.owner, range(20, 21), ValueRole.Return).value()
    val target = base.producer
    val transfer = ValueTransfer.fromCompiler(source, target, ValueTransferKind.SOURCE_CALL_RETURN, evidence).value()
}

private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
