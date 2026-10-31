package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostAutoReload
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedApplicationSettlementTest {
    private val identity =
        IdeHostRuntimeIdentity(
            "/idea",
            "/profile",
            42,
            100,
            "host",
            IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
        )

    @Test
    fun `refresh requires the exact acknowledged transaction and loaded option`() {
        var native = HostNativeSettlement.Settled
        var preparation = false
        val admission = HostedApplicationAdmission()
        val enabled = identity.copy(autoReload = HostAutoReload.ENABLED)
        val owner = HostedApplicationSettlement(admission, enabled, { native }, { preparation })
        val command = IdeLifecycleCommand.RefreshHostBundle("host", "update")
        assertEquals(Refinement.Rejected(IdeLifecycleFailure.REQUEST_CONFLICT), owner.admitRefresh(command))
        owner.settle(IdeLifecycleCommand.SettleHost("host", "update"))
        assertEquals(
            Refinement.Rejected(IdeLifecycleFailure.WRONG_HOST),
            owner.admitRefresh(command.copy(host = "foreign")),
        )
        assertEquals(
            Refinement.Rejected(IdeLifecycleFailure.REQUEST_CONFLICT),
            owner.admitRefresh(command.copy(transaction = "foreign")),
        )
        preparation = true
        assertEquals(Refinement.Rejected(IdeLifecycleFailure.PROJECT_BUSY), owner.admitRefresh(command))
        preparation = false
        native = HostNativeSettlement.UnresolvedMutation
        assertEquals(Refinement.Rejected(IdeLifecycleFailure.MUTATION_RECOVERY_REQUIRED), owner.admitRefresh(command))
        native = HostNativeSettlement.Settled
        val ticket = (owner.admitRefresh(command) as Refinement.Refined).value
        var effects = 0
        native = HostNativeSettlement.Busy
        assertEquals(
            HostRefreshSubmission.Rejected(IdeLifecycleFailure.PROJECT_BUSY),
            owner.dispatchRefresh(ticket) {
                effects++
                HostRefreshSubmission.Submitted
            },
        )
        assertEquals(0, effects)
        val disabled =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                identity,
                { HostNativeSettlement.Settled },
                { false },
            )
        disabled.settle(IdeLifecycleCommand.SettleHost("host", "update"))
        assertEquals(Refinement.Rejected(IdeLifecycleFailure.CAPABILITY_MISMATCH), disabled.admitRefresh(command))
    }

    @Test
    fun `an active semantic lease prevents a refresh ticket even with idle native work`() {
        val admission = HostedApplicationAdmission()
        val lease = (admission.enter() as HostedApplicationAdmission.Admission.Admitted).lease
        val owner =
            HostedApplicationSettlement(
                admission,
                identity.copy(autoReload = HostAutoReload.ENABLED),
                { HostNativeSettlement.Settled },
                { false },
            )
        assertEquals(
            IdeLifecycleResult.HostSettling("host", "update"),
            owner.settle(IdeLifecycleCommand.SettleHost("host", "update")),
        )
        val command = IdeLifecycleCommand.RefreshHostBundle("host", "update")
        assertEquals(Refinement.Rejected(IdeLifecycleFailure.REQUEST_CONFLICT), owner.admitRefresh(command))
        lease.close()
        owner.settle(IdeLifecycleCommand.SettleHost("host", "update"))
        assertTrue(owner.admitRefresh(command) is Refinement.Refined)
    }

    @Test
    fun `another settlement owner cannot consume a ticket from an identical runtime identity`() {
        val enabled = identity.copy(autoReload = HostAutoReload.ENABLED)
        val first =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                enabled,
                { HostNativeSettlement.Settled },
                { false },
            )
        val second =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                enabled,
                { HostNativeSettlement.Settled },
                { false },
            )
        first.settle(IdeLifecycleCommand.SettleHost("host", "update"))
        second.settle(IdeLifecycleCommand.SettleHost("host", "update"))
        val ticket =
            (first.admitRefresh(IdeLifecycleCommand.RefreshHostBundle("host", "update")) as Refinement.Refined).value
        assertEquals(
            HostRefreshSubmission.Rejected(IdeLifecycleFailure.WRONG_HOST),
            second.dispatchRefresh(ticket) { error("foreign ticket reached SDK effects") },
        )
    }

    @Test
    fun `client completion does not establish native preparation or mutation settlement`() {
        val admission = HostedApplicationAdmission()
        var native = HostNativeSettlement.Busy
        val owner =
            HostedApplicationSettlement(admission, identity, { native }, { native == HostNativeSettlement.Busy })
        val command = IdeLifecycleCommand.SettleHost("host", "update")
        assertEquals(IdeLifecycleResult.HostSettling("host", "update"), owner.settle(command))
        assertFalse(owner.canUnload())
        native = HostNativeSettlement.UnresolvedMutation
        assertEquals(IdeLifecycleResult.Blocked(IdeLifecycleFailure.MUTATION_RECOVERY_REQUIRED), owner.settle(command))
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.MUTATION_RECOVERY_REQUIRED),
            owner.resume(IdeLifecycleCommand.ResumeHost("host", "update")),
        )
        assertFalse(owner.canUnload())
        native = HostNativeSettlement.Settled
        assertEquals(IdeLifecycleResult.HostSettled("host", "update", identity), owner.settle(command))
        assertTrue(owner.canUnload())
        assertEquals(
            IdeLifecycleResult.HostStopRequested("host", "update", identity),
            owner.stop(IdeLifecycleCommand.StopHost("host", "update")),
        )
        assertEquals(
            IdeLifecycleResult.HostResumed("host", "update"),
            owner.resume(IdeLifecycleCommand.ResumeHost("host", "update")),
        )
        assertFalse(owner.canUnload())
    }

    @Test
    fun `a foreign Host or transaction cannot stop or reopen an admitted Host`() {
        val admission = HostedApplicationAdmission()
        val owner = HostedApplicationSettlement(admission, identity, { HostNativeSettlement.Settled }, { false })
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.WRONG_HOST),
            owner.settle(IdeLifecycleCommand.SettleHost("foreign", "update")),
        )
        assertFalse(admission.fencedAndIdle())
        owner.settle(IdeLifecycleCommand.SettleHost("host", "update"))
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.PROJECT_BUSY),
            owner.stop(IdeLifecycleCommand.StopHost("host", "foreign")),
        )
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.REQUEST_CONFLICT),
            owner.resume(IdeLifecycleCommand.ResumeHost("host", "foreign")),
        )
        assertTrue(owner.canUnload())
    }
}
