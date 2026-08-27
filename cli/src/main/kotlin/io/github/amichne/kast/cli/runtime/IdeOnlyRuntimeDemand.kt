package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId

fun interface RootRuntimeDemander {
    /**
     * Proof transition: `CanonicalRoot -> RuntimeAdmission`.
     *
     * Establishes one reachable endpoint for the exact root without requiring a caller-provided
     * endpoint. [RuntimeAdmissionFailure] is the closed expected failure.
     */
    fun demand(root: CanonicalRoot): RuntimeAdmission
}

/** Adapts the explicit legacy locator/demander pair behind one root-level admission boundary. */
internal class LocatedRuntimeDemander(
    private val locator: RuntimeEndpointLocator,
    private val demander: RuntimeDemander,
) : RootRuntimeDemander {
    override fun demand(root: CanonicalRoot): RuntimeAdmission {
        val requested = when (val resolution = locator.locate(root)) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.ENDPOINT_UNAVAILABLE,
            )
        }
        if (requested.root != root) {
            return RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ENDPOINT_UNAVAILABLE)
        }
        return when (val admission = demander.demand(root, requested)) {
            is RuntimeAdmission.Ready -> if (admission.endpoint == requested) {
                admission
            } else {
                RuntimeAdmission.Rejected(RuntimeAdmissionFailure.ENDPOINT_UNAVAILABLE)
            }
            is RuntimeAdmission.Rejected -> admission
        }
    }
}

/** Refines an exact canonical root only through the already-running IDE endpoint admission. */
class IdeOnlyRuntimeDemander(
    private val endpointAdmitter: IdeEndpointAdmitter,
    private val runtimeId: SemanticRuntimeId,
) : RootRuntimeDemander {
    /**
     * Proof transition: `CanonicalRoot -> RuntimeAdmission`.
     *
     * Establishes that the exact-root IDE descriptor is compatible, live, and reachable before
     * issuing the runtime endpoint used by wire transport. Missing or incompatible IDE evidence
     * remains [RuntimeAdmissionFailure.IDE_ENDPOINT_REJECTED]. Raw socket extraction is confined
     * to the endpoint-to-transport boundary.
     */
    override fun demand(root: CanonicalRoot): RuntimeAdmission {
        val admitted = when (val admission = endpointAdmitter.admit(root)) {
            is IdeEndpointAdmission.Complete -> admission.endpoint
            is IdeEndpointAdmission.Rejected -> return RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.IDE_ENDPOINT_REJECTED,
            )
        }
        return when (val endpoint = RuntimeEndpoint.at(root, runtimeId, admitted.socketPath)) {
            is RuntimeEndpointResolution.Resolved -> RuntimeAdmission.Ready(endpoint.endpoint)
            is RuntimeEndpointResolution.Rejected -> RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.IDE_ENDPOINT_REJECTED,
            )
        }
    }
}
