package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.CompleteCallbackFlowGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.StaticCallbackEdge
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueSite

/** Select an exact actual occurrence; the shared factory still proves its root reachability and supply. */
internal class NativeCallbackInputSelection(
    private val source: ValueSite,
    private val binding: NativeCallbackArgumentBinding.Mapped,
    private val supplier: CallbackParameterSupplier,
) {
    fun owner(
        enclosing: RelationEndpoint,
        body: RelationCallableBody.Anonymous,
        graph: CompleteCallbackFlowGraph,
    ): Refinement<ValueExecutionOwner, NativeCallbackInputFailure> {
        val selected =
            graph.edges.filterIsInstance<StaticCallbackEdge.Invoke>().singleOrNull { edge ->
                edge.target.body == body && edge.source.supply == supplier.binding && matchesInvocation(edge)
            }
        if (selected == null) return callbackInputRejected()
        return when (
            val admitted =
                ValueExecutionOwner.callback(
                    enclosing = enclosing,
                    body = body,
                    graph = graph,
                    supply = supplier.binding,
                    invocation = selected.evidence,
                )
        ) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> callbackInputRejected(admitted.failure)
        }
    }

    private fun matchesInvocation(edge: StaticCallbackEdge.Invoke): Boolean {
        val occurrence = edge.evidence.occurrence
        if (occurrence.file != source.enclosing.file || occurrence.range != binding.invocationRange) return false
        val owner = edge.evidence.owner
        return owner is RelationCallableBody.Named && matchesOwner(owner)
    }

    private fun matchesOwner(owner: RelationCallableBody.Named): Boolean =
        owner.file == source.enclosing.file &&
            owner.range == source.enclosing.range &&
            owner.compilerIdentity == source.enclosing.compilerIdentity
}
