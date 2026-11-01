package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ValueLoopProtocolTest {
    @Test
    fun `implicit results retain both loop occurrence and selected operation`() {
        val first = protocol(start = 20, end = 70)
        val second = protocol(start = 80, end = 130)
        val iteration = ValueLoopIteration.fromCompilerNormalTrue(first)
        assertEquals(first.iterator, first.iteratorResult.invocation)
        assertEquals(first.next, iteration.elementResult.invocation)
        assertEquals(first.iterator.range, first.next.range)
        assertNotEquals(first.iteratorResult, iteration.elementResult)
        assertNotEquals(first.iteratorResult, second.iteratorResult)
        assertNotEquals(iteration.elementResult, ValueLoopIteration.fromCompilerNormalTrue(second).elementResult)
    }

    @Test
    fun `one protocol cannot mix loop occurrences owners or semantic bases`() {
        val local = protocol(start = 20, end = 70)
        val anotherLoop = protocol(start = 80, end = 130)
        val anotherBasis = protocol(start = 20, end = 70, generation = 2)
        val anotherOwner =
            ValueInvocation.fromCompiler(
                    flowEndpoint(name = "otherCaller", start = 0, end = 200),
                    local.hasNext.range,
                    local.hasNext.callable,
                )
                .flowRefined()
        for (foreign in listOf(anotherLoop.hasNext, anotherBasis.hasNext, anotherOwner)) {
            assertEquals(
                Refinement.Rejected(ValueLoopProtocolFailure.CALL_CONTEXT_MISMATCH),
                ValueLoopProtocol.fromCompiler(local.iterator, foreign, local.next),
            )
        }
        assertEquals(
            Refinement.Rejected(ValueLoopProtocolFailure.OPERATION_IDENTITY_MISMATCH),
            ValueLoopProtocol.fromCompiler(local.iterator, local.iterator, local.next),
        )
        assertEquals(
            Refinement.Rejected(ValueLoopProtocolFailure.OPERATION_IDENTITY_MISMATCH),
            ValueLoopProtocol.fromCompiler(local.next, local.hasNext, local.iterator),
        )
    }

    private fun protocol(start: Int, end: Int, generation: Long = 1): ValueLoopProtocol {
        val caller = flowEndpoint(name = "caller", start = 0, end = 200, generation = generation)
        val iterator = flowEndpoint(name = "Source.iterator", start = 300, end = 330, generation = generation)
        val hasNext = flowEndpoint(name = "Cursor.hasNext", start = 340, end = 370, generation = generation)
        val next = flowEndpoint(name = "Cursor.next", start = 380, end = 410, generation = generation)
        return ValueLoopProtocol.fromCompiler(
                iterator = ValueInvocation.fromCompiler(caller, flowRange(start, end), iterator).flowRefined(),
                hasNext = ValueInvocation.fromCompiler(caller, flowRange(start, end), hasNext).flowRefined(),
                next = ValueInvocation.fromCompiler(caller, flowRange(start, end), next).flowRefined(),
            )
            .flowRefined()
    }
}
