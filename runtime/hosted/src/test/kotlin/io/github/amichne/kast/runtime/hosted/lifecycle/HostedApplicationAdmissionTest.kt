package io.github.amichne.kast.runtime.hosted.lifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedApplicationAdmissionTest {
    @Test
    fun `a settlement fence rejects new work while admitted Host work remains active`() {
        val owner = HostedApplicationAdmission()
        val query = (owner.enter() as HostedApplicationAdmission.Admission.Admitted).lease
        val preparation = (owner.enter() as HostedApplicationAdmission.Admission.Admitted).lease
        assertEquals(HostedApplicationAdmission.Settlement.Busy(2), owner.settle("update"))
        assertEquals(HostedApplicationAdmission.Admission.Fenced, owner.enter())
        assertFalse(owner.fencedAndIdle())
        query.close()
        assertEquals(HostedApplicationAdmission.Settlement.Busy(1), owner.settle("update"))
        preparation.close()
        assertEquals(HostedApplicationAdmission.Settlement.Idle, owner.settle("update"))
        assertTrue(owner.fencedAndIdle())
    }

    @Test
    fun `another transaction cannot acquire or remove the Host fence`() {
        val owner = HostedApplicationAdmission()
        assertEquals(HostedApplicationAdmission.Settlement.Idle, owner.settle("original"))
        assertEquals(HostedApplicationAdmission.Settlement.Conflict, owner.settle("foreign"))
        assertEquals(HostedApplicationAdmission.Resumption.Conflict, owner.resume("foreign"))
        assertEquals(HostedApplicationAdmission.Admission.Fenced, owner.enter())
        assertEquals(HostedApplicationAdmission.Resumption.Resumed, owner.resume("original"))
        assertFalse(owner.fencedAndIdle())
        (owner.enter() as HostedApplicationAdmission.Admission.Admitted).lease.close()
    }

    @Test
    fun `the idle fence waits for the full limit after the last lease and then rejects new work`() {
        var now = 0L
        val owner = HostedApplicationAdmission { now }
        val query = (owner.enter() as HostedApplicationAdmission.Admission.Admitted).lease
        now = 1_000
        assertEquals(HostedApplicationAdmission.IdleFence.Active, owner.fenceIfIdle("idle", 100))
        query.close()
        now = 1_099
        assertEquals(HostedApplicationAdmission.IdleFence.NotDue, owner.fenceIfIdle("idle", 100))
        now = 1_100
        assertEquals(HostedApplicationAdmission.IdleFence.Fenced, owner.fenceIfIdle("idle", 100))
        assertEquals(HostedApplicationAdmission.Admission.Fenced, owner.enter())
        assertEquals(HostedApplicationAdmission.IdleFence.AlreadyFenced, owner.fenceIfIdle("idle", 100))
        assertEquals(HostedApplicationAdmission.Settlement.Conflict, owner.settle("explicit"))
    }

    @Test
    fun `native work and resumption restart the idle clock`() {
        var now = 0L
        val owner = HostedApplicationAdmission { now }
        now = 90
        owner.observeNativeWork()
        now = 189
        assertEquals(HostedApplicationAdmission.IdleFence.NotDue, owner.fenceIfIdle("idle", 100))
        now = 190
        assertEquals(HostedApplicationAdmission.IdleFence.Fenced, owner.fenceIfIdle("idle", 100))
        now = 10_000
        assertEquals(HostedApplicationAdmission.Resumption.Resumed, owner.resume("idle"))
        now = 10_099
        assertEquals(HostedApplicationAdmission.IdleFence.NotDue, owner.fenceIfIdle("idle", 100))
    }

    @Test
    fun `an explicit settlement fence is never taken over by the idle clock`() {
        var now = 0L
        val owner = HostedApplicationAdmission { now }
        assertEquals(HostedApplicationAdmission.Settlement.Idle, owner.settle("explicit"))
        now = 1_000_000
        assertEquals(HostedApplicationAdmission.IdleFence.AlreadyFenced, owner.fenceIfIdle("idle", 100))
        assertEquals(HostedApplicationAdmission.Resumption.Resumed, owner.resume("explicit"))
    }

    @Test
    fun `a reused lease cannot manufacture Host settlement`() {
        val owner = HostedApplicationAdmission()
        val first = (owner.enter() as HostedApplicationAdmission.Admission.Admitted).lease
        val second = (owner.enter() as HostedApplicationAdmission.Admission.Admitted).lease
        first.close()
        assertThrows(IllegalStateException::class.java) { first.close() }
        assertEquals(HostedApplicationAdmission.Settlement.Busy(1), owner.settle("update"))
        assertEquals(HostedApplicationAdmission.Resumption.Conflict, owner.resume("update"))
        second.close()
    }
}
