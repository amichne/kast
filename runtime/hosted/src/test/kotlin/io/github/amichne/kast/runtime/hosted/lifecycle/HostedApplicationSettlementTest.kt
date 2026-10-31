package io.github.amichne.kast.runtime.hosted.lifecycle

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
