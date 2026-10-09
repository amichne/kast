package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** Invocation routes alone do not retain forwarding branches that never invoke the supplied callback. */
sealed interface CallbackForwardingEvidence {
    data object InvocationRoutes : CallbackForwardingEvidence

    data class ExhaustedGraph(val graph: CompleteCallbackForwardingGraph) : CallbackForwardingEvidence
}

/**
 * Exhausted compiler inventories for a finite, supplier-independent formal graph. Cycles are retained edges, not
 * recursive path enumeration. The native owner must exhaust each reachable formal body before admission.
 */
class CompleteCallbackForwardingGraph
private constructor(
    val root: CallbackParameterIdentity,
    val formals: List<CallbackParameterIdentity>,
    val forwardings: List<CallbackParameterForwarding>,
) {
    val retainedBytes: Long = forwardings.fold(root.retainedBytes) { bytes, edge -> bytes.addBytes(edge.retainedBytes) }

    internal fun admitInvocations(
        formal: CallbackParameterIdentity,
        invocations: List<CallbackParameterInvocation>,
    ): Refinement<Unit, CallbackInvocationFlowFailure> {
        if (formal.callable.lease.identity != root.callable.lease.identity)
            return Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
        if (formal != root) return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
        val edges = forwardings.toSet()
        if (invocations.any { invocation -> invocation.forwardings.any { it !in edges } })
            return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
        return admitInvocationRoutes(
            root.callable.lease.identity,
            root.callable,
            root.position,
            root.parameter,
            invocations,
        )
    }

    companion object {
        fun fromCompiler(
            root: CallbackParameterIdentity,
            formals: List<CallbackParameterIdentity>,
            forwardings: List<CallbackParameterForwarding>,
            scan: CallbackInvocationScan,
            obligations: Set<CallbackInvocationFlowCause> = emptySet(),
        ): Refinement<CompleteCallbackForwardingGraph, CallbackInvocationFlowFailure> {
            if (scan != CallbackInvocationScan.EXHAUSTIVE || !admitsExhaustiveCallbackScan(obligations))
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_SCAN_PROOF)
            if (formals.any { it.callable.lease.identity != root.callable.lease.identity })
                return Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
            val adjacency =
                when (val admitted = admitForwardingInventory(root, formals, forwardings)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            if (reachableFormals(root, adjacency) != formals.toSet())
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
            return Refinement.Refined(
                CompleteCallbackForwardingGraph(
                    root,
                    Collections.unmodifiableList(formals.toList()),
                    Collections.unmodifiableList(forwardings.toList()),
                )
            )
        }
    }
}

internal fun CallbackArgumentBinding.formalIdentity():
    Refinement<CallbackParameterIdentity, CallbackInvocationFlowFailure> =
    when (val admitted = CallbackParameterIdentity.fromCompiler(invocation.callable, position, parameter)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
    }

internal fun admitCallbackForwardingEvidence(
    binding: CallbackBindingEvidence,
    scan: CallbackInvocationScan,
    invocations: List<CallbackParameterInvocation>,
    forwarding: CallbackForwardingEvidence,
    obligations: Set<CallbackInvocationFlowCause>,
): Refinement<Unit, CallbackInvocationFlowFailure> =
    when (forwarding) {
        CallbackForwardingEvidence.InvocationRoutes -> Refinement.Refined(Unit)
        is CallbackForwardingEvidence.ExhaustedGraph -> {
            if (
                scan == CallbackInvocationScan.NOT_APPLICABLE ||
                    (scan == CallbackInvocationScan.INCOMPLETE && obligations.isEmpty())
            )
                Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_SCAN_PROOF)
            else {
                val formal =
                    when (binding) {
                        is CallbackBindingEvidence.Bound -> binding.binding.formalIdentity()
                        is CallbackBindingEvidence.Default -> Refinement.Refined(binding.binding.parameter)
                        is CallbackBindingEvidence.DependencyContract,
                        is CallbackBindingEvidence.Direct,
                        is CallbackBindingEvidence.Unavailable ->
                            Refinement.Rejected(CallbackInvocationFlowFailure.UNBOUND_INVOCATION)
                    }
                when (formal) {
                    is Refinement.Refined -> forwarding.graph.admitInvocations(formal.value, invocations)
                    is Refinement.Rejected -> formal
                }
            }
        }
    }

private fun admitForwardingInventory(
    root: CallbackParameterIdentity,
    formals: List<CallbackParameterIdentity>,
    forwardings: List<CallbackParameterForwarding>,
): Refinement<Map<CallbackParameterIdentity, List<CallbackParameterIdentity>>, CallbackInvocationFlowFailure> {
    val inventory = formals.toSet()
    if (root !in inventory || inventory.size != formals.size || forwardings.distinct().size != forwardings.size)
        return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
    val adjacency = linkedMapOf<CallbackParameterIdentity, MutableList<CallbackParameterIdentity>>()
    for (edge in forwardings) {
        val target =
            when (val admitted = edge.target.formalIdentity()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        if (edge.source !in inventory || target !in inventory)
            return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
        adjacency.getOrPut(edge.source) { mutableListOf() } += target
    }
    return Refinement.Refined(adjacency)
}

private fun reachableFormals(
    root: CallbackParameterIdentity,
    adjacency: Map<CallbackParameterIdentity, List<CallbackParameterIdentity>>,
): Set<CallbackParameterIdentity> {
    val reached = linkedSetOf(root)
    val frontier = ArrayDeque<CallbackParameterIdentity>().apply { add(root) }
    while (frontier.isNotEmpty()) {
        for (target in adjacency[frontier.removeFirst()].orEmpty()) {
            if (reached.add(target)) frontier.addLast(target)
        }
    }
    return reached
}
