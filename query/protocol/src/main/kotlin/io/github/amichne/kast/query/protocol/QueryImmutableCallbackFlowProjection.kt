package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackUseDocument
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlow
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue

internal fun ImmutableCallbackInvocationFlow.projectImmutableFlow(
    projection: CallbackProjection
): QueryImmutableCallbackFlowDocument? {
    val root =
        when (val result = ImmutableCallbackValue.fromCompiler(origin, source, source, emptyList())) {
            is Refinement.Refined -> result.value.projectImmutableValue(projection) ?: return null
            is Refinement.Rejected -> return null
        }
    val projectedUses = uses.map { it.projectUse(projection) ?: return null }
    val projected =
        when (val result = BoundedProtocolList.create(projectedUses)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return null
        }
    val causes =
        when (
            val result =
                BoundedProtocolList.create(obligations.sortedBy { it.ordinal }.map { it.protocolCallbackDocument() })
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return null
        }
    return when (
        val result = QueryImmutableCallbackFlowDocument.create(root, projected, causes, scan.protocolCallbackDocument())
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> null
    }
}

internal fun ImmutableCallbackInvocationUse.projectUse(
    projection: CallbackProjection
): QueryImmutableCallbackUseDocument? {
    return when (this) {
        is ImmutableCallbackInvocationUse.Unused ->
            QueryImmutableCallbackUseDocument.Unused(value.projectImmutableValue(projection) ?: return null)
        is ImmutableCallbackInvocationUse.Direct -> projectDirect(projection)
        is ImmutableCallbackInvocationUse.Supplied ->
            QueryImmutableCallbackUseDocument.Supplied(
                supplier.projectCallbackSupplier(projection) ?: return null,
                when (
                    val result =
                        BoundedProtocolList.create(summary.invocations.map { projection.invocation(it) ?: return null })
                ) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return null
                },
                projection.forwardingEvidence(summary.forwarding) ?: return null,
            )
    }
}

private fun ImmutableCallbackInvocationUse.Direct.projectDirect(
    projection: CallbackProjection
): QueryImmutableCallbackUseDocument.Direct? {
    val basis =
        when (val result = binding.basis.impactDocument()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return null
        }
    return QueryImmutableCallbackUseDocument.Direct(
        value.projectImmutableValue(projection) ?: return null,
        QueryCallbackBindingDocument.Direct(
            basis,
            projection.occurrence(binding.occurrence) ?: return null,
            projection.body(binding.owner) ?: return null,
        ),
    )
}
