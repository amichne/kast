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

interface RootRuntimeDemander {
    /**
     * Proof transition: `CanonicalRoot -> RuntimeAdmission`.
     *
     * Establishes one reachable endpoint for the exact root without requiring a caller-provided
     * endpoint. [RuntimeAdmissionFailure] is the closed expected failure.
     */
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

/** Adapts the explicit legacy locator/demander pair behind one root-level admission boundary. */
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
    override fun demand(
        root: CanonicalRoot,
        demand: HostedRuntimeDemand,
        startup: RuntimeStartupRequest,
    ): RuntimeAdmission {
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
                        RuntimeAdmissionFailure.IdeVariantUnavailable
                    HostedRuntimeDemand.Lifecycle,
                    is HostedRuntimeDemand.Operation,
                        -> RuntimeAdmissionFailure.IdeCapabilityUnavailable
                },
            )
        }
        return when (val endpoint = RuntimeEndpoint.at(root, runtimeId, admitted.socketPath)) {
            is RuntimeEndpointResolution.Resolved -> RuntimeAdmission.Ready(endpoint.endpoint)
            is RuntimeEndpointResolution.Rejected -> RuntimeAdmission.Rejected(
                RuntimeAdmissionFailure.IdeSocketMismatch,
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
 * CLI runtime boundary's closed failure protocol. A descriptor rejection carries its nested
 * descriptor and compatibility evidence through the runtime boundary.
 */
private fun IdeEndpointAdmissionFailure.toRuntimeAdmissionFailure(): RuntimeAdmissionFailure =
    when (this) {
        is IdeEndpointAdmissionFailure.InvalidRoot -> RuntimeAdmissionFailure.IdeRootInvalid
        is IdeEndpointAdmissionFailure.LocationRejected ->
            RuntimeAdmissionFailure.IdeLocationRejected
        is IdeEndpointAdmissionFailure.DescriptorReadRejected ->
            RuntimeAdmissionFailure.IdeDescriptorReadRejected
        is IdeEndpointAdmissionFailure.DescriptorRejected ->
            RuntimeAdmissionFailure.IdeDescriptorRejected(failure)
        IdeEndpointAdmissionFailure.RootMismatch -> RuntimeAdmissionFailure.IdeRootMismatch
        IdeEndpointAdmissionFailure.SocketMismatch -> RuntimeAdmissionFailure.IdeSocketMismatch
        IdeEndpointAdmissionFailure.ProcessUnavailable ->
            RuntimeAdmissionFailure.IdeProcessUnavailable
        IdeEndpointAdmissionFailure.ProcessObservationRejected ->
            RuntimeAdmissionFailure.IdeProcessObservationRejected
        IdeEndpointAdmissionFailure.EndpointUnreachable ->
            RuntimeAdmissionFailure.IdeEndpointUnreachable
    }
