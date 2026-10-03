package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QueryImpactPeerBoundaryTest {
    @Test
    fun `same root moved epoch stays stale rather than becoming a peer`() {
        val f = QueryImpactPeerTestFixture(root = "/fixture")
        assertEquals(
            Refinement.Rejected(QueryImpactPeerProofFailure.SAME_SOURCE_ROOT),
            QueryImpactPeerBoundary.admit(f.server.lease, f.model, f.admission),
        )
    }

    @Test
    fun `wrong source full authority cannot adopt a reviewed peer link`() {
        val f = QueryImpactPeerTestFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactPeerProofFailure.SOURCE_BASIS_MISMATCH),
            QueryImpactPeerBoundary.admit(f.peerLease, f.model, f.admission),
        )
    }

    @Test
    fun `same owner and compiler signature at another exact range cannot replace selected target`() {
        val f = QueryImpactPeerTestFixture()
        val changed = changedModel(f)
        assertEquals(
            Refinement.Rejected(QueryImpactPeerProofFailure.TARGET_IDENTITY_MISMATCH),
            QueryImpactPeerBoundary.admit(f.server.lease, changed, f.admission),
        )
        val wrongConnection = BoundaryArrival.connect(changed.source, changed).peerValue()
        assertEquals(
            Refinement.Rejected(QueryImpactPeerProofFailure.CONNECTION_MISMATCH),
            f.boundary.admitConnection(wrongConnection),
        )
        val exact = BoundaryArrival.connect(f.model.source, f.model).peerValue()
        assertSame(exact, f.boundary.admitConnection(exact).peerValue())
    }

    @Test
    fun `duplicate or undeclared peer proof fails before any source is admitted`() {
        val f = QueryImpactPeerTestFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactSourceFailure.DUPLICATE_PEER_BOUNDARY),
            f.source(listOf(f.boundary, f.boundary)),
        )
        val model = changedModel(f)
        val request =
            ValueSiteRevalidationRequest.create(
                    f.request.enclosing,
                    model.target.site.range,
                    ValueSiteRoleClaim.PropertyAssignment,
                    f.request.budget,
                )
                .peerValue()
        val selected =
            QueryImpactRequestedSite.admit(
                    request,
                    RevalidatedValueSite.fromCompiler(request, model.target.site).peerValue(),
                    RelationWorkCount.parse(1).peerValue(),
                )
                .peerValue()
        val admitted = QueryImpactPeerSiteAdmission.admit(selected, f.receipt).peerValue()
        val different = QueryImpactPeerBoundary.admit(f.server.lease, model, admitted).peerValue()
        assertEquals(
            Refinement.Rejected(QueryImpactSourceFailure.UNDECLARED_PEER_BOUNDARY),
            f.source(listOf(different)),
        )
    }

    private fun changedModel(f: QueryImpactPeerTestFixture): BoundaryModel.Continuation {
        val site =
            ValueSite.fromCompiler(
                    f.owner,
                    ExactDeclarationTextRange.parse(60, 61).peerValue(),
                    ValueRole.PropertyAssignment,
                )
                .peerValue()
        val target = BoundaryPosition.at(site, f.targetPosition.kind, f.targetPosition.contract, f.targetPosition.slot)
        return BoundaryModel.Continuation.admit(
                f.model.reference,
                f.sourcePosition.reference,
                target.reference,
                f.sourcePosition,
                target,
                f.model.assumptions,
            )
            .peerValue()
    }
}
