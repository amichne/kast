package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationWorkCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactPeerAdmissionTest {
    @Test
    fun `elapsed receipt rejects negative and admits exact ceil boundary without overflow`() {
        val f = QueryImpactPeerTestFixture()
        val grant =
            f.receipt.grant.copy(
                resources =
                    f.receipt.grant.resources.copy(elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1).peerValue())
            )
        assertEquals(
            Refinement.Rejected(QueryImpactPeerElapsedNanosFailure.NEGATIVE),
            QueryImpactPeerElapsedNanos.parse(-1),
        )
        assertEquals(1_000_000L, receipt(f, grant, nanos = 1_000_000).peerValue().elapsed.value)
        assertEquals(
            Refinement.Rejected(QueryImpactPeerProofFailure.TIME_RECEIPT_EXCEEDS_GRANT),
            receipt(f, grant, nanos = 1_000_001),
        )
        assertEquals(
            Refinement.Rejected(QueryImpactPeerProofFailure.TIME_RECEIPT_EXCEEDS_GRANT),
            receipt(f, grant, nanos = Long.MAX_VALUE),
        )
    }

    @Test
    fun `aggregate work rejects one unit beyond actual child grant`() {
        val f = QueryImpactPeerTestFixture()
        val grant =
            f.receipt.grant.copy(
                resources = f.receipt.grant.resources.copy(workUnitLimit = WorkUnitLimit.parse(3).peerValue())
            )
        assertEquals(3L, receipt(f, grant, work = 3).peerValue().examinedWorkUnits.value)
        assertEquals(
            Refinement.Rejected(QueryImpactPeerProofFailure.WORK_RECEIPT_EXCEEDS_GRANT),
            receipt(f, grant, work = 4),
        )
    }

    @Test
    fun `site native work cannot exceed independently completed aggregate receipt`() {
        val f = QueryImpactPeerTestFixture()
        val emptyWork = receipt(f, f.receipt.grant, work = 0).peerValue()
        assertEquals(
            Refinement.Rejected(QueryImpactPeerProofFailure.SITE_WORK_EXCEEDS_ACQUISITION_WORK),
            QueryImpactPeerSiteAdmission.admit(f.selection, emptyWork),
        )
        val admitted = QueryImpactPeerSiteAdmission.admit(f.selection, f.receipt).peerValue()
        assertSame(f.selection, admitted.selection)
        assertSame(f.receipt, admitted.acquisition)
    }

    @Test
    fun `native selected grant cannot exceed completed work time results or bytes`() {
        val f = QueryImpactPeerTestFixture()
        val original = f.receipt.grant
        val reduced =
            listOf(
                original.copy(resources = original.resources.copy(workUnitLimit = WorkUnitLimit.parse(1).peerValue())),
                original.copy(
                    resources = original.resources.copy(elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1).peerValue())
                ),
                original.copy(resources = original.resources.copy(resultLimit = ResultLimit.parse(1).peerValue())),
                original.copy(returnedBytes = RelationByteLimit.parse(1).peerValue()),
            )
        for (grant in reduced) {
            val completed = receipt(f, grant, work = 1).peerValue()
            assertEquals(
                Refinement.Rejected(QueryImpactPeerProofFailure.SITE_GRANT_EXCEEDS_ACQUISITION_GRANT),
                QueryImpactPeerSiteAdmission.admit(f.selection, completed),
            )
        }
    }

    @Test
    fun `completed authority must equal selected request and native proof authority`() {
        val selected = QueryImpactPeerTestFixture()
        val moved = QueryImpactPeerTestFixture(generation = 20)
        assertEquals(
            Refinement.Rejected(QueryImpactPeerProofFailure.TARGET_BASIS_MISMATCH),
            QueryImpactPeerSiteAdmission.admit(selected.selection, moved.receipt),
        )
    }

    @Test
    fun `existing graph charges shared selection receipt once and each new admission cell`() {
        val f = QueryImpactPeerTestFixture()
        val graph = QueryImpactRetainedGraph()
        val first = graph.peerSiteAdmission(f.admission)
        val reused = graph.peerSiteAdmission(f.admission)
        val another = graph.peerSiteAdmission(QueryImpactPeerSiteAdmission.admit(f.selection, f.receipt).peerValue())
        assertTrue(first > another)
        assertTrue(another > reused)
        assertTrue(reused > 0L)
    }

    private fun receipt(
        f: QueryImpactPeerTestFixture,
        grant: RelationBudget,
        work: Long = 1,
        nanos: Long = 1,
    ) =
        QueryImpactPeerAcquisitionReceipt.fromCompletedRead(
            f.peerLease,
            grant,
            RelationWorkCount.parse(work).peerValue(),
            QueryImpactPeerElapsedNanos.parse(nanos).peerValue(),
        )
}
