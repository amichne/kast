package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.wire.metadata.HostedCapabilityIntent

sealed interface HostedRuntimeDemand {
    data class Operation(val operation: CanonicalOperation) : HostedRuntimeDemand
    data class ChangePlan(val intent: ChangeIntentDocument) : HostedRuntimeDemand
    data object Lifecycle : HostedRuntimeDemand
}

fun interface RootRuntimeDemander {
    /**
     * Proof transition: `CanonicalRoot -> RuntimeAdmission`.
     *
     * Establishes one reachable endpoint for the exact root without requiring a caller-provided
     * endpoint. [RuntimeAdmissionFailure] is the closed expected failure.
     */
    fun demand(root: CanonicalRoot, demand: HostedRuntimeDemand): RuntimeAdmission
}

/** Adapts the explicit legacy locator/demander pair behind one root-level admission boundary. */
internal class LocatedRuntimeDemander(
    private val locator: RuntimeEndpointLocator,
    private val demander: RuntimeDemander,
) : RootRuntimeDemander {
    override fun demand(root: CanonicalRoot, demand: HostedRuntimeDemand): RuntimeAdmission {
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
     * retains its exact closed [RuntimeAdmissionFailure]. Raw socket extraction is confined to the
     * endpoint-to-transport boundary.
     */
    override fun demand(root: CanonicalRoot, demand: HostedRuntimeDemand): RuntimeAdmission {
        val admitted = when (val admission = endpointAdmitter.admit(root)) {
            is IdeEndpointAdmission.Complete -> admission.endpoint
            is IdeEndpointAdmission.Rejected -> return RuntimeAdmission.Rejected(
                admission.failure.toRuntimeAdmissionFailure(),
            )
        }
        if (!admitted.supports(demand)) {
            return RuntimeAdmission.Rejected(
                when (demand) {
                    is HostedRuntimeDemand.ChangePlan ->
                        RuntimeAdmissionFailure.IDE_VARIANT_UNAVAILABLE
                    HostedRuntimeDemand.Lifecycle,
                    is HostedRuntimeDemand.Operation,
                        -> RuntimeAdmissionFailure.IDE_CAPABILITY_UNAVAILABLE
                },
            )
        }
        return when (val endpoint = RuntimeEndpoint.at(root, runtimeId, admitted.socketPath)) {
            is RuntimeEndpointResolution.Resolved -> RuntimeAdmission.Ready(endpoint.endpoint)
            is RuntimeEndpointResolution.Rejected -> RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.IDE_SOCKET_MISMATCH,
            )
        }
    }
}

private fun AdmittedIdeEndpoint.supports(demand: HostedRuntimeDemand): Boolean = when (demand) {
    HostedRuntimeDemand.Lifecycle -> true
    is HostedRuntimeDemand.Operation -> descriptor.capabilities.supports(demand.operation)
    is HostedRuntimeDemand.ChangePlan -> when (demand.intent) {
        is ChangeIntentDocument.AddDeclaration -> descriptor.capabilities.supports(
            CanonicalOperation.CHANGE_PLAN,
            HostedCapabilityIntent.ADD_DECLARATION,
        )
        is ChangeIntentDocument.AddFile,
        is ChangeIntentDocument.RenameSymbol,
        is ChangeIntentDocument.ReplaceDeclaration,
            -> false
    }
}

/**
 * Proof transition: `IdeEndpointAdmissionFailure -> RuntimeAdmissionFailure`.
 *
 * Preserves the exact rejected admission stage while refining the IDE adapter's failure into the
 * CLI runtime boundary's closed failure protocol. Nested evidence remains owned by the endpoint
 * admitter; only the finite stage leaves that adapter.
 */
private fun IdeEndpointAdmissionFailure.toRuntimeAdmissionFailure(): RuntimeAdmissionFailure =
    when (this) {
        is IdeEndpointAdmissionFailure.InvalidRoot -> RuntimeAdmissionFailure.IDE_ROOT_INVALID
        is IdeEndpointAdmissionFailure.LocationRejected ->
            RuntimeAdmissionFailure.IDE_LOCATION_REJECTED
        is IdeEndpointAdmissionFailure.DescriptorReadRejected ->
            RuntimeAdmissionFailure.IDE_DESCRIPTOR_READ_REJECTED
        is IdeEndpointAdmissionFailure.DescriptorRejected ->
            RuntimeAdmissionFailure.IDE_DESCRIPTOR_REJECTED
        IdeEndpointAdmissionFailure.RootMismatch -> RuntimeAdmissionFailure.IDE_ROOT_MISMATCH
        IdeEndpointAdmissionFailure.SocketMismatch -> RuntimeAdmissionFailure.IDE_SOCKET_MISMATCH
        IdeEndpointAdmissionFailure.ProcessUnavailable ->
            RuntimeAdmissionFailure.IDE_PROCESS_UNAVAILABLE
        IdeEndpointAdmissionFailure.ProcessObservationRejected ->
            RuntimeAdmissionFailure.IDE_PROCESS_OBSERVATION_REJECTED
        IdeEndpointAdmissionFailure.EndpointUnreachable ->
            RuntimeAdmissionFailure.IDE_ENDPOINT_UNREACHABLE
    }
