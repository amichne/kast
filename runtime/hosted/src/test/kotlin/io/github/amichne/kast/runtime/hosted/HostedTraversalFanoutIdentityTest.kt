package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedTraversalFanoutIdentityTest {
    @Test
    fun `retained bounded fanout cannot change its edge allowance within the same strategy`() = runTest {
        val fixture = HostedTraversalPagingFixture.create()
        val authority = fixture.owner.authority
        val strategy = TraversalStrategyDocument.BoundedFanOut(count(3))
        val request = fixture.request.copy(strategy = strategy)
        val outcome =
            fixture.outcome.copy(
                evidence =
                    fixture.outcome.evidence.copy(payload = fixture.outcome.evidence.payload.copy(strategy = strategy))
            )
        val pages = HostedQueryContinuations.Active(authority, ReadLimits.Default).traversalOutputs
        val issued = pages.issue(request, authority, outcome) as HostedOutputRetention.Retained
        assertEquals(outcome, pages.restore(issued.token, request, authority))
        assertEquals(
            OperationOutcome.Rejected(TraversalRunRejection.CONTINUATION_REQUEST_MISMATCH),
            pages.restore(
                issued.token,
                request.copy(strategy = TraversalStrategyDocument.BoundedFanOut(count(4))),
                authority,
            ),
        )
        assertEquals(issued, pages.issue(request, authority, outcome))
    }

    private fun count(value: Int) = (ProtocolCount.parse(value) as Refinement.Refined).value
}
