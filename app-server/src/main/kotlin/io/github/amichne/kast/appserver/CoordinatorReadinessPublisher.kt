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
        val cleanup = BrokerStartupStage.DESKTOP_DISCOVERY_CLEANUP
        activity.started(cleanup)
        val failure =
            when (val outcome = discovery.release(target)) {
                DesktopDiscoveryOutcome.Ready -> {
                    activity.completed(cleanup)
                    BrokerServerFailure.READINESS_REJECTED
                }
                is DesktopDiscoveryOutcome.Rejected -> {
                    activity.rejected(cleanup, BrokerStartupRejection.DesktopDiscovery(outcome.failure))
                    BrokerServerFailure.DESKTOP_DISCOVERY_REJECTED
                }
            }
        readiness.reject(failure)
        return CoordinatorReadinessPublication.Rejected(failure)
    }
}
