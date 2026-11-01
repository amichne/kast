package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CompleteCallbackFlowGraph
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtFunction

/** Only a complete source invocation graph can authorize argument entry; declared effects are not payload proof. */
internal fun nativeCallbackInputOwner(
    read: CallbackInvocationFlowRead,
    enclosing: RelationEndpoint,
    lexical: CompilerGroundedSymbolEvidence,
    literal: KtFunction,
    selection: NativeCallbackInputSelection,
): Refinement<NativeValueExecution, NativeCallbackInputFailure> {
    val body: RelationCallableBody.Anonymous
    val graph =
        when (read) {
            is CallbackInvocationFlowRead.Observed -> {
                body = read.flow.body
                CompleteCallbackFlowGraph.admit(read.flow)
            }
            is CallbackInvocationFlowRead.Immutable -> {
                val origin = read.flow.origin
                if (origin !is ImmutableCallbackValueOrigin.Anonymous)
                    return callbackInputRejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                body = origin.body
                val root =
                    when (val admitted = RelationCallableBody.Named.fromCompiler(lexical)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return callbackInputRejected()
                    }
                CompleteCallbackFlowGraph.admit(read.flow, root)
            }
            is CallbackInvocationFlowRead.Unavailable ->
                return Refinement.Rejected(NativeCallbackInputFailure.Callback(read.cause))
            is CallbackInvocationFlowRead.ContractRejected ->
                return Refinement.Rejected(NativeCallbackInputFailure.Contract(read.cause))
        }
    return when (graph) {
        is Refinement.Rejected -> Refinement.Rejected(NativeCallbackInputFailure.Graph(graph.failure))
        is Refinement.Refined ->
            when (val admitted = selection.owner(enclosing, body, graph.value)) {
                is Refinement.Refined -> Refinement.Refined(NativeValueExecution(literal, admitted.value))
                is Refinement.Rejected -> admitted
            }
    }
}
