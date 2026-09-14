package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedTraversalStoreTest {
    @Test
    fun `retained traversal rejects changed graph identity and owner then retirement removes its token`() = runTest {
        val fixture = HostedTraversalPagingFixture.create()
        val other = RelationPagingFixture(RelationPagingFixture.published().authority, "other")
        val owner = HostedQueryContinuations.Active(fixture.owner.authority, ReadLimits.Default)
        val pages = owner.traversalOutputs
        val issued =
            pages.issue(fixture.request, fixture.owner.authority, fixture.outcome) as HostedOutputRetention.Retained
        val changed =
            listOf(
                fixture.request.copy(exactSelector = other.exact),
                fixture.request.copy(relation = RelationKindDocument.CALLERS),
                fixture.request.copy(maximumDepth = ProtocolCount.parse(3).proven()),
                fixture.request.copy(
                    strategy = TraversalStrategyDocument.BoundedFanOut(ProtocolCount.parse(1).proven())
                ),
            )
        for (request in changed) {
            assertEquals(
                OperationOutcome.Rejected(TraversalRunRejection.CONTINUATION_REQUEST_MISMATCH),
                pages.restore(issued.token, request, fixture.owner.authority),
            )
        }
        assertEquals(
            OperationOutcome.Rejected(TraversalRunRejection.CONTINUATION_REQUEST_MISMATCH),
            pages.restore(issued.token, fixture.request, other.authority),
        )
        assertEquals(fixture.outcome, pages.restore(issued.token, fixture.request, fixture.owner.authority))
        owner.clear()
        assertEquals(
            OperationOutcome.Rejected(TraversalRunRejection.CONTINUATION_UNAVAILABLE),
            pages.restore(issued.token, fixture.request, fixture.owner.authority),
        )
    }

    @Test
    fun `traversal replay does not renew retention age at the TTL boundary`() = runTest {
        val fixture = HostedTraversalPagingFixture.create()
        var now = 0L
        val limits = ReadLimits.resolve(environment = mapOf("KAST_READ_QUERY_CONTINUATION_TTL_MILLIS" to "1")).proven()
        val pages = hostedTraversalOutputPages(limits) { now }
        val issued =
            pages.issue(fixture.request, fixture.owner.authority, fixture.outcome) as HostedOutputRetention.Retained
        for (age in listOf(999_999L, 1_000_000L)) {
            now = age
            assertEquals(fixture.outcome, pages.restore(issued.token, fixture.request, fixture.owner.authority))
            assertEquals(issued, pages.issue(fixture.request, fixture.owner.authority, fixture.outcome))
        }
        now = 1_000_001L
        assertEquals(
            OperationOutcome.Rejected(TraversalRunRejection.CONTINUATION_UNAVAILABLE),
            pages.restore(issued.token, fixture.request, fixture.owner.authority),
        )
    }

    @Test
    fun `retained traversal exceeding byte capacity is rejected before token publication`() = runTest {
        val fixture = HostedTraversalPagingFixture.create()
        val limits =
            ReadLimits.resolve(
                    environment =
                        mapOf(
                            "KAST_READ_QUERY_CONTINUATION_BYTES" to "1",
                            "KAST_READ_QUERY_CHECKPOINT_BYTES" to "1",
                        )
                )
                .proven()
        val pages = hostedTraversalOutputPages(limits)
        assertEquals(
            HostedOutputRetention.CapacityExceeded,
            pages.issue(fixture.request, fixture.owner.authority, fixture.outcome),
        )
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
