package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackOwnerExecutionDocument
import io.github.amichne.kast.relation.contract.CallbackOwnerExecution

internal fun CallbackOwnerExecution.projectOwnerExecution(
    projection: CallbackProjection
): QueryCallbackOwnerExecutionDocument? {
    return when (this) {
        CallbackOwnerExecution.Unproven -> QueryCallbackOwnerExecutionDocument.Unproven
        is CallbackOwnerExecution.Immutable ->
            QueryCallbackOwnerExecutionDocument.Immutable(
                flow.projectImmutableFlow(projection) ?: return null,
                QueryCallbackBodyDocument.Named(projection.callable(owner.evidence) ?: return null),
            )
        is CallbackOwnerExecution.Observed ->
            QueryCallbackOwnerExecutionDocument.Observed(projection.observedFlow(flow) ?: return null)
    }
}
