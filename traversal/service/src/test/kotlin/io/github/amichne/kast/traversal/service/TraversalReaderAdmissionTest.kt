package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TraversalReaderAdmissionTest {
    @Test
    fun `reader cannot widen identity scope meaning or one hop budget`() = runTest {
        val fixture = TraversalTestFixture()
        val a = fixture.selector("a", 0)
        val b = fixture.selector("b", 10)
        val plan = fixture.plan(a)
        val escalatingReader = OneHopRelationReader { request ->
            fixture.completeRead(request.copy(node = TraversalNode.start(b)), emptyList())
        }
        assertEquals(
            TraversalResult.Rejected(TraversalRejection.ReaderContractViolation),
            TraversalService(escalatingReader).run(plan),
        )
    }
}
