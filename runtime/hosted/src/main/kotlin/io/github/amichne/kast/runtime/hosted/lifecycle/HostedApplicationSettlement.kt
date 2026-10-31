package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.util.concurrent.atomic.AtomicReference

internal enum class HostNativeSettlement {
    Settled,
    Busy,
    UnresolvedMutation,
}

/** One application-level owner converts Host work and durable mutation facts into settlement evidence. */
internal class HostedApplicationSettlement(
    private val admission: HostedApplicationAdmission,
    private val identity: IdeHostRuntimeIdentity,
    private val inspect: () -> HostNativeSettlement,
    private val hasPreparation: () -> Boolean,
) {
    private sealed interface Acknowledgment {
        data object Absent : Acknowledgment

        data class Admitted(val transaction: String) : Acknowledgment
    }

    private val acknowledgment = AtomicReference<Acknowledgment>(Acknowledgment.Absent)

    @Synchronized
    fun settle(command: IdeLifecycleCommand.SettleHost): IdeLifecycleResult {
        if (command.host != identity.hostLifetime) return blocked(IdeLifecycleFailure.WRONG_HOST)
        if (!TRANSACTION.matches(command.transaction)) return blocked(IdeLifecycleFailure.INVALID_REQUEST)
        return when (admission.settle(command.transaction)) {
            HostedApplicationAdmission.Settlement.Conflict -> blocked(IdeLifecycleFailure.REQUEST_CONFLICT)
            is HostedApplicationAdmission.Settlement.Busy -> pending(command)
            HostedApplicationAdmission.Settlement.Idle ->
                when (inspect()) {
                    HostNativeSettlement.Busy -> pending(command)
                    HostNativeSettlement.UnresolvedMutation -> blocked(IdeLifecycleFailure.MUTATION_RECOVERY_REQUIRED)
                    HostNativeSettlement.Settled -> {
                        acknowledgment.set(Acknowledgment.Admitted(command.transaction))
                        IdeLifecycleResult.HostSettled(command.host, command.transaction, identity)
                    }
                }
        }
    }

    @Synchronized
    fun resume(command: IdeLifecycleCommand.ResumeHost): IdeLifecycleResult {
        if (command.host != identity.hostLifetime) return blocked(IdeLifecycleFailure.WRONG_HOST)
        when (inspect()) {
            HostNativeSettlement.Busy -> return blocked(IdeLifecycleFailure.PROJECT_BUSY)
            HostNativeSettlement.UnresolvedMutation -> return blocked(IdeLifecycleFailure.MUTATION_RECOVERY_REQUIRED)
            HostNativeSettlement.Settled -> Unit
        }
        return when (admission.resume(command.transaction)) {
            HostedApplicationAdmission.Resumption.Resumed -> {
                acknowledgment.set(Acknowledgment.Absent)
                IdeLifecycleResult.HostResumed(command.host, command.transaction)
            }
            HostedApplicationAdmission.Resumption.Conflict -> blocked(IdeLifecycleFailure.REQUEST_CONFLICT)
        }
    }

    @Synchronized
    fun stop(command: IdeLifecycleCommand.StopHost): IdeLifecycleResult {
        if (command.host != identity.hostLifetime) return blocked(IdeLifecycleFailure.WRONG_HOST)
        if (!admission.settledFor(command.transaction) || inspect() != HostNativeSettlement.Settled)
            return blocked(IdeLifecycleFailure.PROJECT_BUSY)
        return IdeLifecycleResult.HostStopRequested(command.host, command.transaction, identity)
    }

    @Synchronized
    fun admitRefresh(
        command: IdeLifecycleCommand.RefreshHostBundle
    ): Refinement<HostRefreshTicket, IdeLifecycleFailure> {
        if (command.host != identity.hostLifetime) return Refinement.Rejected(IdeLifecycleFailure.WRONG_HOST)
        return when (val admitted = refreshAdmission(command.transaction)) {
            is Refinement.Refined -> Refinement.Refined(HostRefreshTicket(this, identity, command.transaction))
            is Refinement.Rejected -> admitted
        }
    }

    @Synchronized
    fun dispatchRefresh(ticket: HostRefreshTicket, effect: () -> HostRefreshSubmission): HostRefreshSubmission {
        if (ticket.owner !== this || ticket.identity != identity)
            return HostRefreshSubmission.Rejected(IdeLifecycleFailure.WRONG_HOST)
        return when (val admitted = refreshAdmission(ticket.transaction)) {
            is Refinement.Refined -> effect()
            is Refinement.Rejected -> HostRefreshSubmission.Rejected(admitted.failure)
        }
    }

    private fun refreshAdmission(transaction: String): Refinement<Unit, IdeLifecycleFailure> {
        if (identity.autoReload != io.github.amichne.kast.protocol.contract.HostAutoReload.ENABLED)
            return Refinement.Rejected(IdeLifecycleFailure.CAPABILITY_MISMATCH)
        if (!TRANSACTION.matches(transaction)) return Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
        val acknowledged = acknowledgment.get()
        if (
            !admission.settledFor(transaction) ||
                acknowledged !is Acknowledgment.Admitted ||
                acknowledged.transaction != transaction
        )
            return Refinement.Rejected(IdeLifecycleFailure.REQUEST_CONFLICT)
        if (hasPreparation()) return Refinement.Rejected(IdeLifecycleFailure.PROJECT_BUSY)
        return when (inspect()) {
            HostNativeSettlement.Busy -> Refinement.Rejected(IdeLifecycleFailure.PROJECT_BUSY)
            HostNativeSettlement.UnresolvedMutation ->
                Refinement.Rejected(IdeLifecycleFailure.MUTATION_RECOVERY_REQUIRED)
            HostNativeSettlement.Settled -> Refinement.Refined(Unit)
        }
    }

    /** The synchronous unload veto does no SQLite or filesystem work on the EDT. */
    fun canUnload(): Boolean =
        admission.fencedAndIdle() && acknowledgment.get() is Acknowledgment.Admitted && !hasPreparation()

    private fun blocked(reason: IdeLifecycleFailure) = IdeLifecycleResult.Blocked(reason)

    private fun pending(command: IdeLifecycleCommand.SettleHost) =
        IdeLifecycleResult.HostSettling(command.host, command.transaction)

    companion object {
        private val TRANSACTION = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

internal class HostRefreshTicket
private constructor(
    internal val owner: HostedApplicationSettlement,
    internal val identity: IdeHostRuntimeIdentity,
    val transaction: String,
) {
    val acknowledgment: IdeLifecycleResult.HostRefreshRequested
        get() = IdeLifecycleResult.HostRefreshRequested(identity.hostLifetime, transaction, identity)

    companion object {
        internal operator fun invoke(
            owner: HostedApplicationSettlement,
            identity: IdeHostRuntimeIdentity,
            transaction: String,
        ): HostRefreshTicket = HostRefreshTicket(owner, identity, transaction)
    }
}
