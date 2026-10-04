package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedPeerRoutingTest {
    @Test
    fun `resume and retained result never select a fresh peer acquisition`() {
        val f = HostedPeerSiteFixture()
        val id = "12345678-1234-1234-1234-123456789abc"
        val requests =
            listOf(
                QueryRunRequest.Resume(QueryExecutionContinuation.Pipeline.parse("query:v1:$id").peerValue()),
                QueryRunRequest.ReadResult.valuePaths(QueryResultReference.parse("result:v1:$id").peerValue()),
            )
        for (request in requests) assertEquals(
            Refinement.Refined(emptyList<Any>()),
            request.hostedPeerSelections(f.source.authority),
        )
    }

    @Test
    fun `peer claim comparison retains full current host epoch and reference version`() {
        val f = HostedPeerSiteFixture()
        val current = f.authority.reference
        assertEquals(Refinement.Refined(Unit), f.basis.requireCurrentPeer(current))
        assertEquals(
            Refinement.Rejected(LiveSemanticReadFailure.WRONG_HOST),
            f.basis.requireCurrentPeer(current.copy(host = IdeReadHostLifetime.fromBoundary(UUID(0, 99)))),
        )
        assertEquals(
            Refinement.Rejected(LiveSemanticReadFailure.EPOCH_MOVED),
            f.basis.requireCurrentPeer(
                current.copy(epoch = IdeReadEpochRevision.parse(current.epoch.value + 1).peerValue())
            ),
        )
        assertEquals(
            Refinement.Rejected(LiveSemanticReadFailure.REFERENCE_VERSION_UNSUPPORTED),
            f.basis.requireCurrentPeer(current.copy(version = 2)),
        )
    }
}
