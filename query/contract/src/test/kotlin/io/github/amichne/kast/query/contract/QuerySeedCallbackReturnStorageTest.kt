package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferFailure
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class QuerySeedCallbackReturnStorageTest {
    @Test
    fun `a new operation charges its own record and frame reference without granting a transfer`() {
        val f = QueryImpactLedgerFixture()
        val frame =
            ValueCallFrame.direct(
                    (f.producerWitness.origin as ValueProducerOrigin.Declared).invocation,
                    f.owner.valueExecutionOwner,
                )
                .value()
        val operation = ValueSourceOperation.SeedCallbackReturn(frame)
        val distinct = ValueSourceOperation.SeedCallbackReturn(frame)
        val graph = QueryImpactRetainedGraph()
        frame.storageBytes(graph)
        assertEquals(operation, distinct)
        assertNotSame(operation, distinct)
        // Each incoming reference costs 8 bytes. The distinct operation costs 512, and its frame reference costs 8.
        assertEquals(528L, operation.storageBytes(graph))
        assertEquals(8L, operation.storageBytes(graph))
        assertEquals(528L, distinct.storageBytes(graph))
        val source =
            ValueSite.fromCompiler(
                    f.owner,
                    ExactDeclarationTextRange.parse(20, 21).value(),
                    ValueRole.Return,
                )
                .value()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source,
                f.producer,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueSourceTransfer.fromCompiler(operation),
            ),
        )
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
