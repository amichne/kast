package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Presentation can index existing witnesses, but cannot introduce an edge or discard one from a proven route. */
internal fun QueryCallbackObservationDocument.admitNavigation(): Refinement<Unit, QueryCallbackDocumentFailure> {
    val steps =
        when (val navigation = navigation) {
            QueryCallbackNavigationDocument.NotComputed -> return Refinement.Refined(Unit)
            is QueryCallbackNavigationDocument.Proven -> navigation.steps.values
            is QueryCallbackNavigationDocument.Unproven -> navigation.prefix.values
        }
    val inventory =
        NavigationWitnesses().apply {
            append(flow)
            target(this@admitNavigation)
        }
    val root = QueryCallbackNavigationNodeDocument.Callable(QueryCallbackBodyDocument.Named(lexicalOwner))
    val seen = navigationEdges(steps, root, inventory.edges)
    return when {
        seen == null -> Refinement.Rejected(QueryCallbackDocumentFailure.INVALID_NAVIGATION)
        navigation !is QueryCallbackNavigationDocument.Proven -> Refinement.Refined(Unit)
        !flow.hasNavigationRootProof() || seen != inventory.reachable(root) ->
            Refinement.Rejected(QueryCallbackDocumentFailure.INVALID_NAVIGATION)
        else -> Refinement.Refined(Unit)
    }
}

private fun navigationEdges(
    steps: List<QueryCallbackNavigationStepDocument>,
    root: QueryCallbackNavigationNodeDocument,
    witnesses: Set<NavigationEdge>,
): Set<NavigationEdge>? {
    val depths = mutableMapOf<QueryCallbackNavigationNodeDocument, Int>(root to 0)
    val seen = mutableSetOf<NavigationEdge>()
    for (step in steps) {
        val edge = NavigationEdge(step.source, step.target, step.evidence)
        val sourceDepth = depths[step.source]
        if (sourceDepth == null || step.depth.value != sourceDepth + 1) return null
        if (edge !in witnesses || !seen.add(edge)) return null
        depths.putIfAbsent(step.target, step.depth.value)
    }
    return seen
}

private fun QueryCallbackFlowDocument.hasNavigationRootProof(): Boolean =
    when (this) {
        is QueryCallbackFlowDocument.Observed ->
            obligations.values.isEmpty() &&
                when (binding) {
                    is QueryCallbackBindingDocument.Bound -> scan == QueryCallbackInvocationScanDocument.EXHAUSTIVE
                    is QueryCallbackBindingDocument.Direct -> scan == QueryCallbackInvocationScanDocument.NOT_APPLICABLE
                    is QueryCallbackBindingDocument.Default,
                    is QueryCallbackBindingDocument.DependencyContract,
                    is QueryCallbackBindingDocument.DeferredDependency,
                    is QueryCallbackBindingDocument.Unavailable -> false
                }
        is QueryCallbackFlowDocument.Immutable ->
            flow.obligations.values.isEmpty() &&
                flow.scan == QueryCallbackInvocationScanDocument.EXHAUSTIVE &&
                flow.uses.values.all { it is QueryImmutableCallbackUseDocument.Unused }
        is QueryCallbackFlowDocument.Unavailable,
        is QueryCallbackFlowDocument.ContractRejected -> false
    }

private data class NavigationEdge(
    val source: QueryCallbackNavigationNodeDocument,
    val target: QueryCallbackNavigationNodeDocument,
    val evidence: QueryCallbackNavigationEvidenceDocument,
)

private class NavigationWitnesses {
    val edges = mutableSetOf<NavigationEdge>()

    private val visited = java.util.IdentityHashMap<QueryCallbackFlowDocument, Unit>()

    fun reachable(root: QueryCallbackNavigationNodeDocument): Set<NavigationEdge> {
        val pending = ArrayDeque<QueryCallbackNavigationNodeDocument>().apply { add(root) }
        val reached = mutableSetOf(root)
        val required = mutableSetOf<NavigationEdge>()
        val outgoing = edges.groupBy { it.source }
        while (pending.isNotEmpty()) {
            for (edge in outgoing[pending.removeFirst()].orEmpty()) {
                required.add(edge)
                if (reached.add(edge.target)) pending.add(edge.target)
            }
        }
        return required
    }

    fun append(flow: QueryCallbackFlowDocument) {
        if (visited.put(flow, Unit) != null) return
        when (flow) {
            is QueryCallbackFlowDocument.Observed -> {
                owners(flow.ownerBindings.values)
                when (val binding = flow.binding) {
                    is QueryCallbackBindingDocument.Bound -> {
                        supplied(binding, flow.body, flow.invocations.values, flow.forwarding)
                    }
                    is QueryCallbackBindingDocument.Direct -> {
                        edges.add(
                            NavigationEdge(
                                callable(binding.owner),
                                callable(flow.body),
                                QueryCallbackNavigationEvidenceDocument.DirectInvoke(binding),
                            )
                        )
                    }
                    is QueryCallbackBindingDocument.Default,
                    is QueryCallbackBindingDocument.DependencyContract,
                    is QueryCallbackBindingDocument.DeferredDependency,
                    is QueryCallbackBindingDocument.Unavailable -> Unit
                }
            }
            is QueryCallbackFlowDocument.Immutable -> immutable(flow.flow)
            is QueryCallbackFlowDocument.Unavailable,
            is QueryCallbackFlowDocument.ContractRejected -> Unit
        }
    }

    private fun immutable(flow: QueryImmutableCallbackFlowDocument) {
        owners(flow.ownerBindings.values)
        val origin = flow.sourceValue.origin
        for (use in flow.uses.values) {
            when (use) {
                is QueryImmutableCallbackUseDocument.Supplied -> {
                    owners(use.summaryOwnerBindings.values)
                    if (origin is QueryImmutableCallbackValueOriginDocument.Anonymous && use.value.origin == origin)
                        supplied(use.supplier.binding, origin.body, use.invocations.values, use.forwarding)
                }
                is QueryImmutableCallbackUseDocument.Direct,
                is QueryImmutableCallbackUseDocument.Unused -> Unit
            }
        }
    }

    private fun owners(bindings: List<QueryCallbackBodyBindingDocument>) {
        for (binding in bindings) {
            when (val execution = binding.execution) {
                is QueryCallbackOwnerExecutionDocument.Observed -> append(execution.flow)
                is QueryCallbackOwnerExecutionDocument.Immutable -> immutable(execution.flow)
                QueryCallbackOwnerExecutionDocument.Unproven -> Unit
            }
        }
    }

    private fun supplied(
        binding: QueryCallbackBindingDocument.Bound,
        body: QueryCallbackBodyDocument.Anonymous,
        invocations: List<QueryCallbackInvocationDocument>,
        forwarding: QueryCallbackForwardingEvidenceDocument,
    ) {
        val initial = QueryCallbackNavigationNodeDocument.Formal(binding.parameterIdentity(), binding)
        edges.add(
            NavigationEdge(
                callable(binding.invocationOwner),
                initial,
                QueryCallbackNavigationEvidenceDocument.Supply(binding, body),
            )
        )
        if (forwarding is QueryCallbackForwardingEvidenceDocument.ExhaustedGraph)
            forwarding.forwardings.values.forEach { forward(it, binding) }
        for (invocation in invocations) {
            var source = initial
            for (hop in invocation.forwardings.values) source = forward(hop, binding)
            edges.add(
                NavigationEdge(source, callable(body), QueryCallbackNavigationEvidenceDocument.Invoke(invocation))
            )
        }
    }

    private fun forward(
        forwarding: QueryCallbackForwardingDocument,
        supplier: QueryCallbackBindingDocument.Bound,
    ): QueryCallbackNavigationNodeDocument.Formal {
        val source = QueryCallbackNavigationNodeDocument.Formal(forwarding.source, supplier)
        val target = QueryCallbackNavigationNodeDocument.Formal(forwarding.target.parameterIdentity(), supplier)
        edges.add(NavigationEdge(source, target, QueryCallbackNavigationEvidenceDocument.Forward(forwarding)))
        return target
    }

    fun target(observation: QueryCallbackObservationDocument) {
        val body =
            when (val flow = observation.flow) {
                is QueryCallbackFlowDocument.Observed -> flow.body
                is QueryCallbackFlowDocument.Immutable ->
                    (flow.flow.sourceValue.origin as? QueryImmutableCallbackValueOriginDocument.Anonymous)?.body
                is QueryCallbackFlowDocument.Unavailable,
                is QueryCallbackFlowDocument.ContractRejected -> null
            }
        if (body != null)
            edges.add(
                NavigationEdge(
                    callable(body),
                    callable(QueryCallbackBodyDocument.Named(observation.target)),
                    QueryCallbackNavigationEvidenceDocument.FunctionInvocation(
                        observation.occurrence,
                        observation.namedPolicy,
                    ),
                )
            )
    }

    private fun callable(body: QueryCallbackBodyDocument) = QueryCallbackNavigationNodeDocument.Callable(body)
}
