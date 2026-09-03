package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument

/** The exact installed-sidecar capability required by one CLI action. */
sealed interface HostedRuntimeDemand {
    data class Operation(val operation: CanonicalOperation) : HostedRuntimeDemand
    data class ChangePlan(val intent: ChangeIntentDocument) : HostedRuntimeDemand
    data object Lifecycle : HostedRuntimeDemand
}

/** Admits or starts the sole installed runtime for one canonical root. */
fun interface RootRuntimeDemander {
    fun demand(
        root: CanonicalRoot,
        demand: HostedRuntimeDemand,
        startup: RuntimeStartupRequest,
    ): RuntimeAdmission
}

fun RootRuntimeDemander.demand(
    root: CanonicalRoot,
    demand: HostedRuntimeDemand,
): RuntimeAdmission = demand(root, demand, RuntimeStartupRequest.Default)

/** Adapts a proven exact-root endpoint to the canonical process runtime demander. */
internal class LocatedRuntimeDemander(
    private val locator: RuntimeEndpointLocator,
    private val demander: RuntimeDemander,
) : RootRuntimeDemander {
    override fun demand(
        root: CanonicalRoot,
        demand: HostedRuntimeDemand,
        startup: RuntimeStartupRequest,
    ): RuntimeAdmission {
        val requested = when (val resolution = locator.locate(root)) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.EndpointUnavailable,
            )
        }
        if (requested.root != root) {
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
        }
        return when (val admission = demander.demand(root, requested)) {
            is RuntimeAdmission.Ready -> if (admission.endpoint == requested) {
                admission
            } else {
                RuntimeAdmission.Rejected(RuntimeAdmissionFailure.EndpointUnavailable)
            }
            is RuntimeAdmission.Rejected -> admission
        }
    }
}
