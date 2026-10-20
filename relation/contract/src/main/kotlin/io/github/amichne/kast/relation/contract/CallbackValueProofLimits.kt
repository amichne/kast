package io.github.amichne.kast.relation.contract

/** Counts expanded frame and owner proofs before their canonical text allocates storage. */
internal object CallbackValueProofLimits {
    const val EXCLUSIVE_DEPTH = 64
    const val MAXIMUM_COUNT = 1000

    fun admits(
        invocations: List<CallbackParameterInvocation>,
        owners: List<CallbackBodyBinding> = emptyList(),
        forwarding: CallbackForwardingEvidence = CallbackForwardingEvidence.InvocationRoutes,
    ): Boolean = CallbackProofTraversal().read(CallbackProofNode.Parts(invocations, owners, forwarding))

    fun admits(
        source: ValueSite,
        uses: List<ImmutableCallbackInvocationUse>,
        owners: List<CallbackBodyBinding>,
    ): Boolean = CallbackProofTraversal().read(CallbackProofNode.ImmutableParts(source, uses, owners))

    fun admits(
        flow: CallbackInvocationFlow,
        selected: CallbackParameterInvocation,
        supplying: ValueCallFrame,
    ): Boolean = CallbackProofTraversal().read(CallbackProofNode.Target(flow, selected, supplying))
}

private sealed interface CallbackProofNode {
    data class Parts(
        val invocations: List<CallbackParameterInvocation>,
        val owners: List<CallbackBodyBinding>,
        val forwarding: CallbackForwardingEvidence,
    ) : CallbackProofNode

    data class ImmutableParts(
        val source: ValueSite,
        val uses: List<ImmutableCallbackInvocationUse>,
        val owners: List<CallbackBodyBinding>,
    ) : CallbackProofNode

    data class Target(
        val flow: CallbackInvocationFlow,
        val selected: CallbackParameterInvocation,
        val supplying: ValueCallFrame,
    ) : CallbackProofNode

    data class Flow(val value: CallbackInvocationFlow) : CallbackProofNode

    data class ImmutableFlow(val value: ImmutableCallbackInvocationFlow) : CallbackProofNode

    data class Invocation(val value: CallbackParameterInvocation) : CallbackProofNode

    data class Forwarding(val value: CallbackParameterForwarding) : CallbackProofNode

    data class Owner(val value: CallbackBodyBinding) : CallbackProofNode

    data class Frame(val value: ValueCallFrame) : CallbackProofNode

    data class Site(val value: ValueSite) : CallbackProofNode

    data class Transfer(val value: ValueTransfer) : CallbackProofNode

    data class Evidence(val value: ValueTransferEvidence) : CallbackProofNode

    data class Predicates(val value: ValuePredicates) : CallbackProofNode

    data class Value(val value: ImmutableCallbackValue) : CallbackProofNode

    data class Factory(val value: CallbackFactoryReturn) : CallbackProofNode
}

private enum class CallbackProofExpansion {
    LOCAL_FACTS,
    COMPLETE,
}

private data class CallbackProofVisit(
    val node: CallbackProofNode,
    val depth: Int,
    val expansion: CallbackProofExpansion,
)

/** The queue is local, and repeated immutable subgraphs count again at every expanded edge. */
private class CallbackProofTraversal {
    private val pending = ArrayDeque<CallbackProofVisit>()
    private var count = 0
    private var expansion = CallbackProofExpansion.LOCAL_FACTS

    fun read(root: CallbackProofNode): Boolean {
        val rootDepth = if (root is CallbackProofNode.Target) 1 else 0
        if (rootDepth == 1) {
            count = 1
            expansion = CallbackProofExpansion.COMPLETE
        }
        add(root, rootDepth)
        while (pending.isNotEmpty()) {
            if (!advance(pending.removeLast())) return false
        }
        return true
    }

    private fun advance(visit: CallbackProofVisit): Boolean {
        expansion = visit.expansion
        val counted = visit.node is CallbackProofNode.Frame || visit.node is CallbackProofNode.Owner
        val depth = visit.depth + if (counted) 1 else 0
        if (counted) count++
        if (depth >= CallbackValueProofLimits.EXCLUSIVE_DEPTH || count > CallbackValueProofLimits.MAXIMUM_COUNT)
            return false
        expand(visit.node, depth)
        return true
    }

    private fun add(node: CallbackProofNode, depth: Int) {
        pending.addLast(CallbackProofVisit(node, depth, expansion))
    }

    private fun parts(node: CallbackProofNode.Parts, depth: Int) {
        node.invocations.forEach { add(CallbackProofNode.Invocation(it), depth) }
        node.owners.forEach { add(CallbackProofNode.Owner(it), depth) }
        when (val forwarding = node.forwarding) {
            CallbackForwardingEvidence.InvocationRoutes -> Unit
            is CallbackForwardingEvidence.ExhaustedGraph ->
                forwarding.graph.forwardings.forEach { add(CallbackProofNode.Forwarding(it), depth) }
        }
    }

    private fun expand(node: CallbackProofNode, depth: Int) {
        when (node) {
            is CallbackProofNode.Parts -> parts(node, depth)
            is CallbackProofNode.ImmutableParts -> immutable(node, depth)
            is CallbackProofNode.Target -> {
                expansion = CallbackProofExpansion.COMPLETE
                add(CallbackProofNode.Flow(node.flow), depth)
                add(CallbackProofNode.Invocation(node.selected), depth)
                add(CallbackProofNode.Frame(node.supplying), depth)
            }
            is CallbackProofNode.Flow ->
                parts(
                    CallbackProofNode.Parts(node.value.invocations, node.value.ownerBindings, node.value.forwarding),
                    depth,
                )
            is CallbackProofNode.ImmutableFlow ->
                immutable(
                    CallbackProofNode.ImmutableParts(node.value.source, node.value.uses, node.value.ownerBindings),
                    depth,
                )
            is CallbackProofNode.Invocation -> {
                node.value.callableTransfers.forEach { add(CallbackProofNode.Transfer(it), depth) }
                node.value.forwardings.forEach { add(CallbackProofNode.Forwarding(it), depth) }
            }
            is CallbackProofNode.Forwarding ->
                node.value.callableTransfers.forEach { add(CallbackProofNode.Transfer(it), depth) }
            is CallbackProofNode.Owner -> owner(node.value, depth)
            is CallbackProofNode.Frame -> frame(node.value, depth)
            is CallbackProofNode.Site -> {
                node.value.callStack.frames.forEach { add(CallbackProofNode.Frame(it), depth) }
                add(CallbackProofNode.Predicates(node.value.predicates), depth)
            }
            is CallbackProofNode.Transfer -> {
                add(CallbackProofNode.Site(node.value.source), depth)
                add(CallbackProofNode.Site(node.value.target), depth)
                add(CallbackProofNode.Evidence(node.value.evidence), depth)
            }
            is CallbackProofNode.Evidence -> evidence(node.value, depth)
            is CallbackProofNode.Predicates ->
                node.value.entries.forEach { add(CallbackProofNode.Site(it.subject.occurrence), depth) }
            is CallbackProofNode.Value -> value(node.value, depth)
            is CallbackProofNode.Factory -> factory(node.value, depth)
        }
    }

    private fun value(value: ImmutableCallbackValue, depth: Int) {
        add(CallbackProofNode.Site(value.source), depth)
        add(CallbackProofNode.Site(value.destination), depth)
        value.transfers.forEach { add(CallbackProofNode.Transfer(it), depth) }
        when (val origin = value.origin) {
            is ImmutableCallbackValueOrigin.Returned -> add(CallbackProofNode.Factory(origin.factory), depth)
            is ImmutableCallbackValueOrigin.Anonymous,
            is ImmutableCallbackValueOrigin.Named -> Unit
        }
    }

    private fun owner(owner: CallbackBodyBinding, depth: Int) {
        // Local summaries cache owner execution separately. A full frame proof expands that execution.
        if (expansion == CallbackProofExpansion.LOCAL_FACTS) return
        when (val execution = owner.execution) {
            CallbackOwnerExecution.Unproven -> Unit
            is CallbackOwnerExecution.Observed -> add(CallbackProofNode.Flow(execution.flow), depth)
            is CallbackOwnerExecution.Immutable -> add(CallbackProofNode.ImmutableFlow(execution.flow), depth)
        }
    }

    private fun frame(frame: ValueCallFrame, depth: Int) {
        when (val target = frame.target) {
            ValueCallTarget.Direct,
            is ValueReviewedCallbackTarget,
            is ValueCallTarget.Implementation -> Unit
            is ValueCallbackTarget ->
                add(CallbackProofNode.Target(target.flow, target.selected, target.supplyingFrame), depth)
        }
    }

    private fun evidence(evidence: ValueTransferEvidence, depth: Int) {
        when (evidence) {
            ValueTransferEvidence.Direct,
            is ValueTransferEvidence.StringInterpolation,
            is ValueTransferEvidence.NormalBranchResult -> Unit
            is ValueTransferEvidence.Guarded -> {
                add(CallbackProofNode.Evidence(evidence.delegate), depth)
                add(CallbackProofNode.Predicates(evidence.added), depth)
            }
            is ValueSourceTransfer ->
                when (val operation = evidence.operation) {
                    is ValueSourceOperation.Enter -> add(CallbackProofNode.Frame(operation.frame), depth)
                    is ValueSourceOperation.SeedCallbackEnter -> add(CallbackProofNode.Frame(operation.frame), depth)
                    is ValueSourceOperation.Return -> add(CallbackProofNode.Frame(operation.frame), depth)
                    is ValueSourceOperation.SeedReturn -> add(CallbackProofNode.Frame(operation.frame), depth)
                    is ValueSourceOperation.SeedCallbackReturn -> add(CallbackProofNode.Frame(operation.frame), depth)
                    is ValueSourceOperation.ReadField,
                    is ValueSourceOperation.ConstructField -> Unit
                }
        }
    }

    private fun immutable(node: CallbackProofNode.ImmutableParts, depth: Int) {
        add(CallbackProofNode.Site(node.source), depth)
        node.owners.forEach { add(CallbackProofNode.Owner(it), depth) }
        node.uses.forEach { use ->
            add(CallbackProofNode.Value(use.value), depth)
            when (use) {
                is ImmutableCallbackInvocationUse.Supplied -> {
                    val summary = use.summary
                    parts(
                        CallbackProofNode.Parts(summary.invocations, summary.ownerBindings, summary.forwarding),
                        depth,
                    )
                    when (val selection = use.supplier.selection) {
                        is CallbackSupplierSelection.Explicit -> add(CallbackProofNode.Site(selection.argument), depth)
                        is CallbackSupplierSelection.Default -> Unit
                    }
                }
                is ImmutableCallbackInvocationUse.Direct,
                is ImmutableCallbackInvocationUse.Unused -> Unit
            }
        }
    }

    private fun factory(factory: CallbackFactoryReturn, depth: Int) {
        add(CallbackProofNode.Value(factory.returnedValue), depth)
        factory.captures.forEach { capture ->
            when (val selection = capture.selection) {
                is CallbackFactoryCaptureSelection.Explicit -> add(CallbackProofNode.Site(selection.value), depth)
                is CallbackFactoryCaptureSelection.Default -> Unit
            }
            when (val content = capture.content) {
                CallbackFactoryCaptureContent.Scalar -> Unit
                is CallbackFactoryCaptureContent.Callable -> {
                    content.values.forEach { add(CallbackProofNode.Value(it), depth) }
                    content.invocations.forEach { add(CallbackProofNode.Invocation(it), depth) }
                }
            }
        }
        when (val calls = factory.bodyCalls) {
            CallbackFactoryBodyCalls.NotApplicable -> Unit
            is CallbackFactoryBodyCalls.Exhaustive ->
                calls.calls.forEach { call ->
                    when (call) {
                        is CallbackFactoryBodyCall.Captured -> add(CallbackProofNode.Invocation(call.invocation), depth)
                        is CallbackFactoryBodyCall.Named,
                        is CallbackFactoryBodyCall.Boundary -> Unit
                    }
                }
        }
    }
}
