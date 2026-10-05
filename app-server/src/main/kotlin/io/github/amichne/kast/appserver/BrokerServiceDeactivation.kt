package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement

internal enum class BrokerServiceDeactivationMode {
    STOP,
    DISABLE,
}

internal sealed interface BrokerServiceDeactivationFailure {
    data class Service(val cause: PersistentBrokerServiceFailure) : BrokerServiceDeactivationFailure

    data class Discovery(val cause: DesktopDiscoveryFailure) : BrokerServiceDeactivationFailure

    data object LoginOwnership : BrokerServiceDeactivationFailure
}

/** The published launch receipt retains the exact owner through stop and subsequent cleanup. */
internal class BrokerServiceDeactivation(
    private val host: MacOsPersistentBrokerServiceHost = MacOsPersistentBrokerServiceHost(),
    private val discovery: DesktopDaemonDiscovery = DesktopDaemonDiscovery(),
) {
    fun execute(
        command: BrokerServiceLaunchCommand,
        mode: BrokerServiceDeactivationMode,
    ): Refinement<Unit, BrokerServiceDeactivationFailure> {
        val owner = PublishedBrokerServiceCommand.recover(command) ?: command
        if (
            mode == BrokerServiceDeactivationMode.DISABLE &&
                ServiceLoginAgent.observe(owner) == ServiceLoginAgentObservation.REJECTED
        )
            return Refinement.Rejected(BrokerServiceDeactivationFailure.LoginOwnership)
        when (val stopped = host.stop(owner)) {
            PersistentBrokerServiceAdmission.Ready -> Unit
            is PersistentBrokerServiceAdmission.Rejected ->
                return Refinement.Rejected(BrokerServiceDeactivationFailure.Service(stopped.failure))
        }
        if (mode == BrokerServiceDeactivationMode.DISABLE) {
            when (val released = discovery.release(DesktopDiscoveryTarget.from(owner))) {
                DesktopDiscoveryOutcome.Ready -> Unit
                is DesktopDiscoveryOutcome.Rejected ->
                    return Refinement.Rejected(BrokerServiceDeactivationFailure.Discovery(released.failure))
            }
            if (ServiceLoginAgent.remove(owner) == ServiceLoginAgentChange.REJECTED)
                return Refinement.Rejected(BrokerServiceDeactivationFailure.LoginOwnership)
        }
        return Refinement.Refined(Unit)
    }
}
