package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostAutoReload
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedApplicationSettlementTest {
    private val refreshRoot =
        object : HostBundleRefreshRoot {
            override val preparedMarker =
                (HostRefreshMarker.admit(HostRefreshMarkerSnapshot(100, 100, 10, 10)) as Refinement.Refined).value
            override val path = Path.of("/profile/plugins/kast-ide-hosted")

            override fun submit(completed: () -> Unit) = HostRefreshSubmission.Submitted
        }

    private fun prepared() = Refinement.Refined(refreshRoot)

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
    fun `normal settlement refines to a prepared reload without erasing its proof`() {
        val enabled = identity.copy(autoReload = HostAutoReload.ENABLED)
        var captures = 0
        val owner =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                enabled,
                { HostNativeSettlement.Settled },
                { false },
                {
                    captures++
                    prepared()
                },
            )
        val normal = IdeLifecycleCommand.SettleHost("host", "update")
        val reload = IdeLifecycleCommand.SettleHostForReload("host", "update")
        val refresh = IdeLifecycleCommand.RefreshHostBundle("host", "update")
        assertEquals(IdeLifecycleResult.HostSettled("host", "update", enabled), owner.settle(normal))
        assertEquals(0, captures)
        assertEquals(Refinement.Rejected(IdeLifecycleFailure.CAPABILITY_MISMATCH), owner.admitRefresh(refresh))
        assertEquals(IdeLifecycleResult.HostSettled("host", "update", enabled), owner.settleForReload(reload))
        assertEquals(1, captures)
        val ticket = (owner.admitRefresh(refresh) as Refinement.Refined).value
        assertEquals(IdeLifecycleResult.HostSettled("host", "update", enabled), owner.settle(normal))
        assertEquals(1, captures)
        assertEquals(HostRefreshSubmission.Submitted, owner.dispatchRefresh(ticket) { HostRefreshSubmission.Submitted })
    }

    @Test
    fun `disabled reload rejects preparation but preserves ordinary stop`() {
        val owner =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                identity,
                { HostNativeSettlement.Settled },
                { false },
                { error("disabled reload reached marker preparation") },
            )
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.CAPABILITY_MISMATCH),
            owner.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update")),
        )
        assertEquals(
            IdeLifecycleResult.HostSettled("host", "update", identity),
            owner.settle(IdeLifecycleCommand.SettleHost("host", "update")),
        )
        assertEquals(
            IdeLifecycleResult.HostStopRequested("host", "update", identity),
            owner.stop(IdeLifecycleCommand.StopHost("host", "update")),
        )
    }

    @Test
    fun `normal restart remains legal when enabled refresh metadata is unknown`() {
        val enabled = identity.copy(autoReload = HostAutoReload.ENABLED)
        val owner =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                enabled,
                { HostNativeSettlement.Settled },
                { false },
                { Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE) },
            )
        assertEquals(
            IdeLifecycleResult.HostSettled("host", "update", enabled),
            owner.settle(IdeLifecycleCommand.SettleHost("host", "update")),
        )
        assertEquals(
            IdeLifecycleResult.HostStopRequested("host", "update", enabled),
            owner.stop(IdeLifecycleCommand.StopHost("host", "update")),
        )
    }

    @Test
    fun `disabled reload settles and stops without marker preparation`() {
        val owner =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                identity,
                { HostNativeSettlement.Settled },
                { false },
                { error("disabled reload reached marker preparation") },
            )
        assertEquals(
            IdeLifecycleResult.HostSettled("host", "update", identity),
            owner.settle(IdeLifecycleCommand.SettleHost("host", "update")),
        )
        assertEquals(
            IdeLifecycleResult.HostStopRequested("host", "update", identity),
            owner.stop(IdeLifecycleCommand.StopHost("host", "update")),
        )
    }

    @Test
    fun `unknown prepared cache facts never produce a settled or stop acknowledgment`() {
        val enabled = identity.copy(autoReload = HostAutoReload.ENABLED)
        val invalid = HostRefreshMarkerSnapshot(-1, 100, 10, 10)
        val owner =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                enabled,
                { HostNativeSettlement.Settled },
                { false },
                {
                    when (val marker = HostRefreshMarker.admit(invalid)) {
                        is Refinement.Refined -> error("invalid cache metadata admitted")
                        is Refinement.Rejected -> marker
                    }
                },
            )
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE),
            owner.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update")),
        )
        assertEquals(
            IdeLifecycleResult.Blocked(IdeLifecycleFailure.PROJECT_BUSY),
            owner.stop(IdeLifecycleCommand.StopHost("host", "update")),
        )
    }

    @Test
    fun `refresh requires the exact acknowledged transaction and loaded option`() {
        var native = HostNativeSettlement.Settled
        var preparation = false
        val admission = HostedApplicationAdmission()
        val enabled = identity.copy(autoReload = HostAutoReload.ENABLED)
        val owner = HostedApplicationSettlement(admission, enabled, { native }, { preparation }, ::prepared)
        val command = IdeLifecycleCommand.RefreshHostBundle("host", "update")
        assertEquals(Refinement.Rejected(IdeLifecycleFailure.REQUEST_CONFLICT), owner.admitRefresh(command))
        owner.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update"))
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
                ::prepared,
            )
        disabled.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update"))
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
                ::prepared,
            )
        assertEquals(
            IdeLifecycleResult.HostSettling("host", "update"),
            owner.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update")),
        )
        val command = IdeLifecycleCommand.RefreshHostBundle("host", "update")
        assertEquals(Refinement.Rejected(IdeLifecycleFailure.REQUEST_CONFLICT), owner.admitRefresh(command))
        lease.close()
        owner.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update"))
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
                ::prepared,
            )
        val second =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                enabled,
                { HostNativeSettlement.Settled },
                { false },
                ::prepared,
            )
        first.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update"))
        second.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update"))
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
