package io.github.amichne.kast.appserver

internal sealed interface CoordinatorReadinessPublication {
    data object Ready : CoordinatorReadinessPublication

    data class Rejected(val failure: BrokerServerFailure) : CoordinatorReadinessPublication
}

/** Final activation and rollback retain both readiness and discovery-cleanup evidence. */
internal object CoordinatorReadinessPublisher {
    fun publish(
        readiness: OwnedBrokerServiceReadiness?,
        target: DesktopDiscoveryTarget,
        discovery: DesktopDaemonDiscovery,
        activity: BrokerStartupActivityPublisher,
    ): CoordinatorReadinessPublication {
        val stage = BrokerStartupStage.READINESS_PUBLICATION
        activity.started(stage)
        if (readiness?.ready() != BrokerReadinessTransition.Rejected) {
            activity.completed(stage)
            return CoordinatorReadinessPublication.Ready
        }
        activity.rejected(stage, BrokerStartupRejection.Coordinator(BrokerServerFailure.READINESS_REJECTED))
        val failure =
            when (rollback(target, discovery, activity)) {
                DesktopDiscoveryOutcome.Ready -> BrokerServerFailure.READINESS_REJECTED
                is DesktopDiscoveryOutcome.Rejected -> BrokerServerFailure.DESKTOP_DISCOVERY_REJECTED
            }
        readiness.reject(failure)
        return CoordinatorReadinessPublication.Rejected(failure)
    }

    internal fun rollback(
        target: DesktopDiscoveryTarget,
        discovery: DesktopDaemonDiscovery,
        activity: BrokerStartupActivityPublisher,
    ): DesktopDiscoveryOutcome {
        val stage = BrokerStartupStage.DESKTOP_DISCOVERY_CLEANUP
        activity.started(stage)
        return discovery.release(target).also { outcome ->
            when (outcome) {
                DesktopDiscoveryOutcome.Ready -> activity.completed(stage)
                is DesktopDiscoveryOutcome.Rejected ->
                    activity.rejected(stage, BrokerStartupRejection.DesktopDiscovery(outcome.failure))
            }
        }
    }
}
