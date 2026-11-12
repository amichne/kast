package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueNativeOperation
import io.github.amichne.kast.relation.contract.ValueNativeUnexpectedFailure
import io.github.amichne.kast.relation.contract.ValueNativeUnexpectedKind
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryNativeFailureStorageTest {
    @Test
    fun `rejected reads charge their own bounded diagnostic payload rather than a constant`() {
        val compact = read(emptyList())
        val detailed = read(listOf("io.github.amichne.kast.Adapter.read:" + "x".repeat(500)))
        assertTrue(QueryImpactRetainedGraph().read(detailed) > QueryImpactRetainedGraph().read(compact))
        val graph = QueryImpactRetainedGraph()
        val first = graph.read(detailed)
        assertTrue(first > graph.read(detailed))
    }

    private fun read(frames: List<String>): ValueFlowRead.Rejected =
        ValueFlowRead.Rejected(
            ValueFlowRejection.Native.Unexpected(
                ValueNativeOperation.FLOW_READ,
                ValueNativeUnexpectedFailure.fromBoundary(
                    kind = ValueNativeUnexpectedKind.RUNTIME,
                    exceptionType = "java.lang.IllegalStateException",
                    adapterFrames = frames,
                    limits = ReadLimits.Default,
                ),
            ),
            (RelationWorkCount.parse(1) as Refinement.Refined).value,
        )
}
