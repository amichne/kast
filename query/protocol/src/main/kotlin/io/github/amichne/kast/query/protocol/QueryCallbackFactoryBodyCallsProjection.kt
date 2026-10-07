package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryBodyCallDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFactoryBodyCallsDocument
import io.github.amichne.kast.relation.contract.CallbackFactoryBodyCall
import io.github.amichne.kast.relation.contract.CallbackFactoryBodyCalls

internal fun CallbackFactoryBodyCalls.projectFactoryBodyCalls(
    projection: CallbackProjection
): QueryCallbackFactoryBodyCallsDocument? =
    when (this) {
        CallbackFactoryBodyCalls.NotApplicable -> QueryCallbackFactoryBodyCallsDocument.NotApplicable
        is CallbackFactoryBodyCalls.Exhaustive -> projectExhaustive(projection)
    }

private fun CallbackFactoryBodyCalls.Exhaustive.projectExhaustive(
    projection: CallbackProjection
): QueryCallbackFactoryBodyCallsDocument? {
    val body = projection.body(body) as? QueryCallbackBodyDocument.Anonymous ?: return null
    val calls =
        BoundedProtocolList.create(calls.map { it.projectBodyCall(projection) ?: return null }).bodyValue()
            ?: return null
    return QueryCallbackFactoryBodyCallsDocument.Exhaustive.create(body, calls).bodyValue()
}

private fun CallbackFactoryBodyCall.projectBodyCall(
    projection: CallbackProjection
): QueryCallbackFactoryBodyCallDocument? {
    return when (this) {
        is CallbackFactoryBodyCall.Named ->
            QueryCallbackFactoryBodyCallDocument.Named(
                projection.occurrence(occurrence) ?: return null,
                projection.callable(target) ?: return null,
            )
        is CallbackFactoryBodyCall.Captured ->
            QueryCallbackFactoryBodyCallDocument.Captured(
                projection.invocation(invocation) ?: return null,
                projection.parameter(formal) ?: return null,
            )
        is CallbackFactoryBodyCall.Boundary ->
            QueryCallbackFactoryBodyCallDocument.Boundary(
                projection.occurrence(occurrence) ?: return null,
                callable.protocolDocument() ?: return null,
                disposition.protocolDocument(),
            )
    }
}

private fun <V, F> Refinement<V, F>.bodyValue(): V? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
