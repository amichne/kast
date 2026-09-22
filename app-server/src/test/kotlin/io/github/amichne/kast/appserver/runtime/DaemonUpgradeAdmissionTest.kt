package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DaemonUpgradeAdmissionTest {
    @Test
    fun `pending request keeps identity and observation cannot manufacture quiescence`() {
        val owner = DaemonUpgradeAdmission { requestId }
        val pending = owner.prepare(candidate, setOf(UpgradeBlocker.ACTIVE_TURN)).value()
        assertInstanceOf(UpgradeStatus.Pending::class.java, pending)
        assertEquals(requestId, pending.request.id)
        assertEquals(pending, owner.observe(requestId).value())
        assertEquals(Refinement.Refined(Unit), owner.admitWork())
        val sealed = owner.prepare(candidate, emptySet()).value()
        assertInstanceOf(UpgradeStatus.Sealed::class.java, sealed)
        assertEquals(requestId, sealed.request.id)
        assertEquals(Refinement.Rejected(DaemonUpgradeFailure.ADMISSION_SEALED), owner.admitWork())
    }

    @Test
    fun `conflicting candidate and foreign request cannot release a seal`() {
        val owner = DaemonUpgradeAdmission { requestId }
        val sealed = owner.prepare(candidate, emptySet()).value()
        assertEquals(
            Refinement.Rejected(DaemonUpgradeFailure.CANDIDATE_CONFLICT),
            owner.prepare(otherCandidate, emptySet()),
        )
        assertEquals(Refinement.Rejected(DaemonUpgradeFailure.UNKNOWN_REQUEST), owner.cancel(otherId))
        assertEquals(sealed, owner.observe(requestId).value())
        assertInstanceOf(UpgradeStatus.Cancelled::class.java, owner.cancel(requestId).value())
        assertEquals(Refinement.Refined(Unit), owner.admitWork())
    }

    @Test
    fun `only a sealed request may commit and committed admission cannot reopen`() {
        val owner = DaemonUpgradeAdmission { requestId }
        owner.prepare(candidate, setOf(UpgradeBlocker.INVOCATION_ACTIVE))
        assertEquals(Refinement.Rejected(DaemonUpgradeFailure.NOT_QUIESCENT), owner.commit(requestId))
        owner.prepare(candidate, emptySet())
        val committed = owner.commit(requestId).value()
        assertInstanceOf(UpgradeStatus.Committed::class.java, committed)
        assertEquals(committed, owner.commit(requestId).value())
        assertEquals(Refinement.Rejected(DaemonUpgradeFailure.ALREADY_COMMITTED), owner.cancel(requestId))
        assertEquals(Refinement.Rejected(DaemonUpgradeFailure.ADMISSION_SEALED), owner.admitWork())
    }

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> throw AssertionError("Unexpected rejection: $failure")
        }

    companion object {
        private val candidate = (UpgradeCandidate.admit("a".repeat(64)) as Refinement.Refined).value
        private val otherCandidate = (UpgradeCandidate.admit("b".repeat(64)) as Refinement.Refined).value
        private val requestId =
            (UpgradeRequestId.admit("00000000-0000-0000-0000-000000000001") as Refinement.Refined).value
        private val otherId =
            (UpgradeRequestId.admit("00000000-0000-0000-0000-000000000002") as Refinement.Refined).value
    }
}
