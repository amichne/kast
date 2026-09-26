package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireResponseByteMinimum

/** Reject a supplied allowance that cannot hold even the mandatory envelope identity, before read dispatch. */
internal fun HostedRequest.admitResponseBudget(): Refinement<HostedRequest, HostedEndpointFailure> =
    when (this) {
        is HostedRequest.Query ->
            admit(request.executionBudget, CanonicalOperationWireBindings.queryRun.minimumResponseBytes)
        is HostedRequest.Source ->
            admit(request.executionBudget, CanonicalOperationWireBindings.sourceRead.minimumResponseBytes)
        is HostedRequest.Traversal ->
            admit(request.executionBudget, CanonicalOperationWireBindings.traversalRun.minimumResponseBytes)
        is HostedRequest.Diagnostic ->
            admit(request.executionBudget, CanonicalOperationWireBindings.diagnosticCheck.minimumResponseBytes)
        else -> Refinement.Refined(this)
    }

private fun HostedRequest.admit(
    requested: ExecutionBudgetDocument?,
    minimum: WireResponseByteMinimum,
): Refinement<HostedRequest, HostedEndpointFailure> =
    when (val bytes = requested?.maxReturnedBytes) {
        null -> Refinement.Refined(this)
        else ->
            if (minimum.admits(bytes)) Refinement.Refined(this)
            else Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST)
    }
