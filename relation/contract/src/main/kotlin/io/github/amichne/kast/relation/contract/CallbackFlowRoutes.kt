package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

private fun CallbackFlowComposition.ownerOf(
    flow: CallbackRouteContext,
    owner: RelationCallableBody,
    expected: RelationEndpoint,
): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> =
    when (val admitted = owner(flow.basis, flow.ownerBindings, owner)) {
        is Refinement.Rejected -> admitted
        is Refinement.Refined ->
            if (expected.matchesCallbackRoot(admitted.value)) admitted
            else Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
    }

internal fun CallbackFlowComposition.routes(
    flow: CallbackRouteContext,
    binding: CallbackArgumentBinding,
    invocations: List<CallbackParameterInvocation>,
): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> {
    val root = ownerOf(flow, binding.invocationOwner, binding.invocation.enclosing)
    if (root is Refinement.Rejected) return root
    val formal =
        when (val result = binding.formalIdentity()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return Refinement.Rejected(StaticCallbackGraphFailure.InvalidFlow(result.failure))
        }
    val origin = StaticCallbackNode.Formal(formal, binding)
    when (val target = flow.target) {
        is CallbackRouteTarget.Anonymous ->
            edges +=
                StaticCallbackEdge.Supply(
                    binding.invocationOwner.callbackNode(),
                    origin,
                    StaticCallbackNode.Anonymous(target.body),
                    binding,
                )
        CallbackRouteTarget.RetainedTransport -> Unit
    }
    when (val proof = flow.forwarding) {
        CallbackForwardingEvidence.InvocationRoutes -> Unit
        is CallbackForwardingEvidence.ExhaustedGraph ->
            for (edge in proof.graph.forwardings) {
                when (val admitted = forward(flow, binding, edge)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return admitted
                }
            }
    }
    for (invocation in invocations) {
        when (val admitted = route(flow, origin, invocation)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
    }
    return root
}

private fun CallbackFlowComposition.forward(
    flow: CallbackRouteContext,
    supplier: CallbackArgumentBinding,
    edge: CallbackParameterForwarding,
): Refinement<StaticCallbackNode.Formal, StaticCallbackGraphFailure> {
    when (val admitted = ownerOf(flow, edge.target.invocationOwner, edge.source.callable)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    val target =
        when (val formal = edge.target.formalIdentity()) {
            is Refinement.Refined -> StaticCallbackNode.Formal(formal.value, supplier)
            is Refinement.Rejected -> return Refinement.Rejected(StaticCallbackGraphFailure.InvalidFlow(formal.failure))
        }
    edges += StaticCallbackEdge.Forward(StaticCallbackNode.Formal(edge.source, supplier), target, edge)
    return Refinement.Refined(target)
}

private fun CallbackFlowComposition.route(
    flow: CallbackRouteContext,
    origin: StaticCallbackNode.Formal,
    invocation: CallbackParameterInvocation,
): Refinement<Unit, StaticCallbackGraphFailure> {
    var source = origin
    val seen = mutableSetOf(origin)
    for (edge in invocation.forwardings) {
        if (source.parameter != edge.source)
            return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        source =
            when (val target = forward(flow, origin.supply, edge)) {
                is Refinement.Refined -> target.value
                is Refinement.Rejected -> return target
            }
        if (!seen.add(source) && flow.forwarding == CallbackForwardingEvidence.InvocationRoutes)
            return Refinement.Rejected(StaticCallbackGraphFailure.CyclicRoute)
    }
    when (val admitted = ownerOf(flow, invocation.owner, source.parameter.callable)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    when (val target = flow.target) {
        is CallbackRouteTarget.Anonymous ->
            edges +=
                StaticCallbackEdge.Invoke(
                    source,
                    StaticCallbackNode.Anonymous(target.body),
                    invocation.owner.callbackNode(),
                    invocation,
                )
        CallbackRouteTarget.RetainedTransport -> Unit
    }
    return Refinement.Refined(Unit)
}

internal data class CallbackRouteContext(
    val basis: SemanticReadIdentity,
    val target: CallbackRouteTarget,
    val ownerBindings: List<CallbackBodyBinding>,
    val forwarding: CallbackForwardingEvidence,
)

internal sealed interface CallbackRouteTarget {
    data class Anonymous(val body: RelationCallableBody.Anonymous) : CallbackRouteTarget

    data object RetainedTransport : CallbackRouteTarget
}

internal fun RelationCallableBody.callbackNode(): StaticCallbackNode.Callable =
    when (this) {
        is RelationCallableBody.Named -> StaticCallbackNode.Named(this)
        is RelationCallableBody.Anonymous -> StaticCallbackNode.Anonymous(this)
    }
