package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

sealed interface ValueExecutionContext {
    data object Declaration : ValueExecutionContext

    /** The complete graph retains supplier occurrences independently of ordinary source-call frames. */
    data class Callback
    internal constructor(
        val body: RelationCallableBody.Anonymous,
        val invocations: List<ValueCaptureInvocation>,
    ) : ValueExecutionContext
}

data class ValueCaptureInvocation
internal constructor(
    val body: RelationCallableBody.Anonymous,
    val supply: CallbackArgumentBinding,
    val invocation: CallbackParameterInvocation,
)

data class ValueExecutionIdentity
internal constructor(
    val host: ValueDeclarationIdentity,
    val basis: SemanticReadIdentity,
    val context: ValueExecutionContext = ValueExecutionContext.Declaration,
)

/** Plain source execution belongs to one exact compiler-confirmed declaration. */
class ValueExecutionOwner
private constructor(
    val enclosing: RelationEndpoint,
    val context: ValueExecutionContext = ValueExecutionContext.Declaration,
) {
    val basis = enclosing.lease.identity
    val range =
        when (context) {
            ValueExecutionContext.Declaration -> enclosing.range
            is ValueExecutionContext.Callback -> context.body.range
        }
    val identity = ValueExecutionIdentity(enclosing.valueIdentity, basis, context)

    fun belongsTo(host: RelationEndpoint): Boolean = identity.host == host.valueIdentity && basis == host.lease.identity

    fun hasSameProofAs(other: ValueExecutionOwner): Boolean =
        identity == other.identity && enclosing.hasSameProofAs(other.enclosing)

    companion object {
        fun declaration(enclosing: RelationEndpoint): ValueExecutionOwner = ValueExecutionOwner(enclosing)

        fun callback(
            enclosing: RelationEndpoint,
            body: RelationCallableBody.Anonymous,
            graph: CompleteCallbackFlowGraph,
        ): Refinement<ValueExecutionOwner, ValueFlowUnsupportedCause> {
            if (graph.basis != enclosing.lease.identity || !enclosing.matchesCallbackRoot(graph.root))
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            if (body.file != enclosing.file || !enclosing.range.containsValueRange(body.range))
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            if (graph.edges.any { it !is StaticCallbackEdge.Supply && it !is StaticCallbackEdge.Invoke })
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            val invocations =
                graph.edges.filterIsInstance<StaticCallbackEdge.Invoke>().map { edge ->
                    ValueCaptureInvocation(edge.target.body, edge.source.supply, edge.evidence)
                }
            if (invocations.none { it.body == body })
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            if (invocations.groupBy { it.body }.values.any { it.size != 1 })
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            return Refinement.Refined(
                ValueExecutionOwner(
                    enclosing,
                    ValueExecutionContext.Callback(
                        body,
                        java.util.Collections.unmodifiableList(invocations),
                    ),
                )
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueExecutionOwner && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}
