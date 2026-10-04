package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/** Pure native-fact fixtures prove query admission only, not installed compiler behavior. */
class QueryImpactPeerSourceTest {
    @Test
    fun `native selected peer continuation remains an admitted source question`() {
        val f = QueryImpactPeerTestFixture()
        val peers = mutableListOf(f.boundary)
        val admitted = f.source(peers)
        val source = assertInstanceOf<Refinement.Refined<QueryImpactSource>>(admitted).value
        peers.clear()
        assertEquals(listOf(f.boundary), source.peerBoundaries)
        assertEquals(listOf(f.model), source.boundaryModels)
        assertSame(f.server.lease, source.lease)
        assertSame(f.selection.site, (source.boundaryModels.single() as BoundaryModel.Continuation).target.site)
        assertEquals(emptyList<QueryImpactRequestedSite>(), source.requestedSites)
        assertTrue(source.retainedBytes > f.selection.site.retainedBytes)
    }

    @Test
    fun `foreign continuation without completed native target proof remains rejected`() {
        val f = QueryImpactPeerTestFixture()
        assertEquals(Refinement.Rejected(QueryImpactSourceFailure.FOREIGN_BASIS), f.source(emptyList()))
    }

    @Test
    fun `completed peer target never authorizes foreign requested site universe`() {
        val f = QueryImpactPeerTestFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactSourceFailure.FOREIGN_BASIS),
            QueryImpactSource.admit(
                listOf(f.server.producerWitness),
                emptyList(),
                listOf(f.model),
                f.server.domain.boundary,
                requestedSites = listOf(f.selection),
                peerBoundaries = listOf(f.boundary),
            ),
        )
    }
}
