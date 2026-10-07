package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackReferenceReceiverDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceFlowDocument
import io.github.amichne.kast.relation.contract.CallbackReferenceReceiver
import io.github.amichne.kast.relation.contract.NamedCallbackReference
import io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow

internal fun NamedCallbackReference.projectNamedReference(
    projection: CallbackProjection
): QueryNamedCallbackReferenceDocument? {
    val projectedFlow = flow.projectFlow(projection) ?: return null
    return QueryNamedCallbackReferenceDocument(
        projection.callable(target) ?: return null,
        receivers.dispatch.projectReceiver(projection) ?: return null,
        receivers.extension.projectReceiver(projection) ?: return null,
        projectedFlow,
    )
}

internal fun CallbackReferenceReceiver.projectReceiver(
    projection: CallbackProjection
): QueryCallbackReferenceReceiverDocument? =
    when (this) {
        CallbackReferenceReceiver.Absent -> QueryCallbackReferenceReceiverDocument.Absent
        CallbackReferenceReceiver.Unbound -> QueryCallbackReferenceReceiverDocument.Unbound
        is CallbackReferenceReceiver.Bound ->
            projection.occurrence(occurrence)?.let { QueryCallbackReferenceReceiverDocument.Bound(it) }
        is CallbackReferenceReceiver.Implicit ->
            projection.callable(declaration)?.let { QueryCallbackReferenceReceiverDocument.Implicit(it) }
    }

private fun NamedCallbackReferenceFlow.projectFlow(
    projection: CallbackProjection
): QueryNamedCallbackReferenceFlowDocument? {
    return when (val value = this) {
        is NamedCallbackReferenceFlow.Immutable ->
            QueryNamedCallbackReferenceFlowDocument.Immutable(
                value.flow.projectImmutableFlow(projection) ?: return null
            )
        is NamedCallbackReferenceFlow.Unavailable ->
            QueryNamedCallbackReferenceFlowDocument.Unavailable(
                when (
                    val causes =
                        io.github.amichne.kast.protocol.contract.QueryCallbackGraphObligationsDocument.from(
                            value.causes.map { it.protocolCallbackDocument() }.sortedBy { it.ordinal }
                        )
                ) {
                    is Refinement.Refined -> causes.value
                    is Refinement.Rejected -> return null
                }
            )
        is NamedCallbackReferenceFlow.Direct -> value.projectDirect(projection)
        is NamedCallbackReferenceFlow.Supplied ->
            QueryNamedCallbackReferenceFlowDocument.Supplied(
                projection.bound(value.binding) ?: return null,
                when (
                    val result =
                        BoundedProtocolList.create(
                            value.summary.invocations.map { projection.invocation(it) ?: return null }
                        )
                ) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return null
                },
                projection.forwardingEvidence(value.summary.forwarding) ?: return null,
            )
    }
}

private fun NamedCallbackReferenceFlow.Direct.projectDirect(
    projection: CallbackProjection
): QueryNamedCallbackReferenceFlowDocument.Direct? {
    val basis =
        when (val result = binding.basis.impactDocument()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return null
        }
    return QueryNamedCallbackReferenceFlowDocument.Direct(
        QueryCallbackBindingDocument.Direct(
            basis,
            projection.occurrence(binding.occurrence) ?: return null,
            projection.body(binding.owner) ?: return null,
        )
    )
}
