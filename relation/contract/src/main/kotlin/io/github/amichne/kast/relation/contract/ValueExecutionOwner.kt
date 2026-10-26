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
        val selectedInvocation: ValueCaptureInvocation,
    ) : ValueExecutionContext

    /** Conditional library value correspondence remains distinct from source invocation evidence. */
    data class ReviewedCallback internal constructor(val binding: ReviewedCallbackValueBinding) : ValueExecutionContext
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
            is ValueExecutionContext.ReviewedCallback -> context.binding.body.range
        }
    val identity = ValueExecutionIdentity(enclosing.valueIdentity, basis, context)

    fun belongsTo(host: RelationEndpoint): Boolean = identity.host == host.valueIdentity && basis == host.lease.identity

    fun hasSameProofAs(other: ValueExecutionOwner): Boolean =
        identity == other.identity && enclosing.hasSameProofAs(other.enclosing)

    /** Retain the admitted route when a wrapper returns into its exact supplier's execution body. */
    fun supplierOf(invocation: ValueCaptureInvocation): Refinement<ValueExecutionOwner, ValueFlowUnsupportedCause> =
        when (val execution = context) {
            ValueExecutionContext.Declaration -> Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            is ValueExecutionContext.ReviewedCallback -> Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            is ValueExecutionContext.Callback ->
                if (invocation != execution.selectedInvocation)
                    Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
                else supplierOwner(invocation.supply.invocationOwner, execution)
        }

    private fun supplierOwner(
        supplier: RelationCallableBody,
        execution: ValueExecutionContext.Callback,
    ): Refinement<ValueExecutionOwner, ValueFlowUnsupportedCause> =
        when (supplier) {
            is RelationCallableBody.Named ->
                if (enclosing.matchesCallbackRoot(supplier.evidence)) Refinement.Refined(declaration(enclosing))
                else Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            is RelationCallableBody.Anonymous -> {
                val selections = execution.invocations.filter { it.body == supplier }
                if (selections.size == 1)
                    Refinement.Refined(
                        ValueExecutionOwner(
                            enclosing,
                            execution.copy(body = supplier, selectedInvocation = selections.single()),
                        )
                    )
                else Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            }
        }

    companion object {
        fun declaration(enclosing: RelationEndpoint): ValueExecutionOwner = ValueExecutionOwner(enclosing)

        fun reviewedCallback(binding: ReviewedCallbackValueBinding): ValueExecutionOwner =
            ValueExecutionOwner(binding.enclosing, ValueExecutionContext.ReviewedCallback(binding))

        fun callback(
            enclosing: RelationEndpoint,
            body: RelationCallableBody.Anonymous,
            graph: CompleteCallbackFlowGraph,
        ): Refinement<ValueExecutionOwner, ValueFlowUnsupportedCause> =
            admitCallback(enclosing, body, graph) { captures -> captures.filter { it.body == body } }

        /** Select one exact supply and invocation from a complete graph; repeated bodies remain distinct executions. */
        fun callback(
            enclosing: RelationEndpoint,
            body: RelationCallableBody.Anonymous,
            graph: CompleteCallbackFlowGraph,
            supply: CallbackArgumentBinding,
            invocation: CallbackParameterInvocation,
        ): Refinement<ValueExecutionOwner, ValueFlowUnsupportedCause> =
            admitCallback(enclosing, body, graph) { captures ->
                captures.filter { it.body == body && it.supply == supply && it.invocation == invocation }
            }

        private fun admitCallback(
            enclosing: RelationEndpoint,
            body: RelationCallableBody.Anonymous,
            graph: CompleteCallbackFlowGraph,
            select: (List<ValueCaptureInvocation>) -> List<ValueCaptureInvocation>,
        ): Refinement<ValueExecutionOwner, ValueFlowUnsupportedCause> {
            if (graph.basis != enclosing.lease.identity || !enclosing.matchesCallbackRoot(graph.root))
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            if (body.file != enclosing.file || !enclosing.range.containsValueRange(body.range))
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            if (graph.edges.any { it !is StaticCallbackEdge.Supply && it !is StaticCallbackEdge.Invoke })
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            // Formal reachability cannot prove an anonymous invocation owner; do not truncate its inventory.
            if (graph.edges.filterIsInstance<StaticCallbackEdge.Invoke>().any { it.owner !is StaticCallbackNode.Named })
                return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            val invocations =
                invokedFromRoot(graph).map { edge ->
                    ValueCaptureInvocation(edge.target.body, edge.source.supply, edge.evidence)
                }
            val selected = select(invocations)
            if (selected.size != 1) return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            return Refinement.Refined(
                ValueExecutionOwner(
                    enclosing,
                    ValueExecutionContext.Callback(
                        body,
                        java.util.Collections.unmodifiableList(invocations),
                        selected.single(),
                    ),
                )
            )
        }

        private fun invokedFromRoot(graph: CompleteCallbackFlowGraph): List<StaticCallbackEdge.Invoke> {
            val outgoing = graph.edges.groupBy { it.source }
            val roots =
                outgoing.keys.filterIsInstance<StaticCallbackNode.Named>().filter {
                    it.callable.evidence == graph.root
                }
            val pending = ArrayDeque<StaticCallbackNode>().apply { addAll(roots) }
            val reached = roots.toMutableSet<StaticCallbackNode>()
            val invocations = mutableListOf<StaticCallbackEdge.Invoke>()
            while (pending.isNotEmpty()) {
                for (edge in outgoing[pending.removeFirst()].orEmpty()) {
                    if (edge is StaticCallbackEdge.Invoke) invocations.add(edge)
                    if (reached.add(edge.target)) pending.add(edge.target)
                }
            }
            return invocations
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueExecutionOwner && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}
