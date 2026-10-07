package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal fun admitStaticCallbackForwardingGraph(
    binding: CallbackArgumentBinding,
    forwarding: CallbackForwardingEvidence,
): Refinement<List<StaticCallbackEdge.Forward>, StaticCallbackGraphFailure> {
    return when (forwarding) {
        CallbackForwardingEvidence.InvocationRoutes -> Refinement.Refined(emptyList())
        is CallbackForwardingEvidence.ExhaustedGraph -> {
            val edges = mutableListOf<StaticCallbackEdge.Forward>()
            for (edge in forwarding.graph.forwardings) {
                when (val admitted = admitForwardingEdge(binding, edge)) {
                    is Refinement.Refined -> edges += admitted.value
                    is Refinement.Rejected -> return admitted
                }
            }
            Refinement.Refined(edges)
        }
    }
}

private fun admitForwardingEdge(
    binding: CallbackArgumentBinding,
    edge: CallbackParameterForwarding,
): Refinement<StaticCallbackEdge.Forward, StaticCallbackGraphFailure> {
    val owner = edge.target.invocationOwner
    if (owner !is RelationCallableBody.Named) return Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
    if (
        edge.source.callable.file != owner.file ||
            edge.source.callable.range != owner.range ||
            edge.source.callable.compilerIdentity != owner.compilerIdentity
    )
        return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
    val target =
        when (val admitted = edge.target.formalIdentity()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return Refinement.Rejected(StaticCallbackGraphFailure.InvalidFlow(admitted.failure))
        }
    return Refinement.Refined(
        StaticCallbackEdge.Forward(
            StaticCallbackNode.Formal(edge.source, binding),
            StaticCallbackNode.Formal(target, binding),
            edge,
        )
    )
}
