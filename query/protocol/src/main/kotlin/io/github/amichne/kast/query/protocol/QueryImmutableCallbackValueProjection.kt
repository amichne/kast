package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueOriginDocument
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin

internal fun ImmutableCallbackValue.projectImmutableValue(
    projection: CallbackProjection
): QueryImmutableCallbackValueDocument? {
    val projected = origin.projectOrigin(projection) ?: return null
    val projectedSource =
        when (val result = source.impactDocument()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return null
        }
    val projectedDestination =
        when (val result = destination.impactDocument()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return null
        }
    val projectedTransfers =
        when (
            val result =
                BoundedProtocolList.create(
                    transfers.map {
                        when (val result = it.impactDocument()) {
                            is Refinement.Refined -> result.value
                            is Refinement.Rejected -> return null
                        }
                    }
                )
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return null
        }
    val callables =
        (listOf(source, destination) + transfers.flatMap { listOf(it.source, it.target) })
            .mapNotNull { (it.role as? io.github.amichne.kast.relation.contract.ValueRole.Argument)?.call?.callable }
            .distinct()
    val invoked =
        when (val result = BoundedProtocolList.create(callables.map { projection.callable(it) ?: return null })) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return null
        }
    return when (
        val result =
            QueryImmutableCallbackValueDocument.create(
                projected,
                projectedSource,
                projectedDestination,
                projectedTransfers,
                invoked,
            )
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> null
    }
}

private fun ImmutableCallbackValueOrigin.projectOrigin(
    projection: CallbackProjection
): QueryImmutableCallbackValueOriginDocument? {
    return when (val value = this) {
        is ImmutableCallbackValueOrigin.Returned ->
            io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueOriginDocument.Returned(
                value.factory.projectFactoryReturn(projection) ?: return null
            )
        is ImmutableCallbackValueOrigin.Anonymous ->
            QueryImmutableCallbackValueOriginDocument.Anonymous(
                projection.body(value.body) as? QueryCallbackBodyDocument.Anonymous ?: return null
            )
        is ImmutableCallbackValueOrigin.Named ->
            QueryImmutableCallbackValueOriginDocument.Named(
                when (val result = value.target.lease.identity.impactDocument()) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return null
                },
                projection.occurrence(value.occurrence) ?: return null,
                projection.callable(value.target) ?: return null,
                value.receivers.dispatch.projectReceiver(projection) ?: return null,
                value.receivers.extension.projectReceiver(projection) ?: return null,
            )
    }
}
