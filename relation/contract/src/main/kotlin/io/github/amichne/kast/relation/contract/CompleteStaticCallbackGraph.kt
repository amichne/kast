package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import java.util.Collections

/**
 * Complete static routes for one compiler-confirmed callback-body target occurrence in its admitted query domain. The
 * enclosing query must separately prove exhaustive observation inventory. Existing flow projection retains all
 * derivation witnesses; this graph neither broadens named CALLERS/CALLEES nor proves runtime activation.
 */
class CompleteStaticCallbackGraph
private constructor(
    val observation: RelationCallbackObservation,
    val nodes: Set<StaticCallbackNode>,
    val edges: List<StaticCallbackEdge>,
) {
    val basis = observation.basis
    val domain = observation.effectiveDomain

    companion object {
        fun admit(
            observation: RelationCallbackObservation
        ): Refinement<CompleteStaticCallbackGraph, StaticCallbackGraphFailure> {
            val flow =
                when (val read = observation.flow) {
                    is CallbackInvocationFlowRead.Immutable -> return immutable(observation, read.flow)
                    is CallbackInvocationFlowRead.Observed -> read.flow
                    is CallbackInvocationFlowRead.Unavailable ->
                        return Refinement.Rejected(StaticCallbackGraphFailure.Unavailable(read.cause))
                    is CallbackInvocationFlowRead.ContractRejected ->
                        return Refinement.Rejected(StaticCallbackGraphFailure.InvalidFlow(read.cause))
                }
            if (flow.obligations.isNotEmpty()) return Refinement.Rejected(StaticCallbackGraphFailure.Unresolved(flow))
            when (val policy = observation.policy) {
                CallbackNamedCallPolicy.AdmittedDirect,
                CallbackNamedCallPolicy.AdmittedInline -> Unit
                is CallbackNamedCallPolicy.Unavailable ->
                    return Refinement.Rejected(StaticCallbackGraphFailure.UnprovenPolicy(policy))
                is CallbackNamedCallPolicy.Excluded ->
                    when (policy.reason) {
                        CallbackExclusionReason.NON_INLINE_ARGUMENT,
                        CallbackExclusionReason.NOINLINE_ARGUMENT,
                        CallbackExclusionReason.CROSSINLINE_ARGUMENT -> Unit
                        CallbackExclusionReason.STORED_CALLBACK,
                        CallbackExclusionReason.RETURNED_CALLBACK,
                        CallbackExclusionReason.DEFAULT_PARAMETER ->
                            return Refinement.Rejected(StaticCallbackGraphFailure.UnprovenPolicy(policy))
                    }
            }
            val edges =
                when (val admitted = StaticCallbackGraphAdmission(observation, flow).edges()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val nodes = edges.nodes()
            if (nodes.any { !it.insideWorkspace(observation.basis.workspaceRoot) })
                return Refinement.Rejected(StaticCallbackGraphFailure.OutsideWorkspace)
            return Refinement.Refined(
                CompleteStaticCallbackGraph(
                    observation,
                    Collections.unmodifiableSet(nodes),
                    Collections.unmodifiableList(edges),
                )
            )
        }

        private fun immutable(
            observation: RelationCallbackObservation,
            flow: ImmutableCallbackInvocationFlow,
        ): Refinement<CompleteStaticCallbackGraph, StaticCallbackGraphFailure> {
            if (flow.obligations.isNotEmpty())
                return Refinement.Rejected(StaticCallbackGraphFailure.ImmutableUnresolved(flow))
            if (flow.scan != CallbackInvocationScan.EXHAUSTIVE)
                return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
            if (observation.policy is CallbackNamedCallPolicy.Unavailable)
                return Refinement.Rejected(StaticCallbackGraphFailure.UnprovenPolicy(observation.policy))
            val origin =
                flow.origin as? ImmutableCallbackValueOrigin.Anonymous
                    ?: return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
            val owner =
                when (val result = RelationCallableBody.Named.fromCompiler(observation.lexicalOwner)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
                }
            if (!flow.source.enclosing.matches(owner))
                return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
            val target =
                when (val result = RelationCallableBody.Named.fromCompiler(observation.target)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(StaticCallbackGraphFailure.NonCallableTarget)
                }
            val body = StaticCallbackNode.Anonymous(origin.body)
            val edges =
                listOf<StaticCallbackEdge>(
                    StaticCallbackEdge.ImmutableUses(StaticCallbackNode.Named(owner), body, flow),
                    StaticCallbackEdge.BodyTarget(
                        body,
                        StaticCallbackNode.Named(target),
                        observation.occurrence,
                        observation.policy,
                    ),
                )
            val nodes = edges.nodes()
            if (
                nodes.any { !it.insideWorkspace(observation.basis.workspaceRoot) } ||
                    flow.admitCallbackWorkspace(observation.basis.workspaceRoot) is Refinement.Rejected
            )
                return Refinement.Rejected(StaticCallbackGraphFailure.OutsideWorkspace)
            return Refinement.Refined(
                CompleteStaticCallbackGraph(
                    observation,
                    Collections.unmodifiableSet(nodes),
                    Collections.unmodifiableList(edges),
                )
            )
        }
    }
}

private class StaticCallbackGraphAdmission(
    private val observation: RelationCallbackObservation,
    private val flow: CallbackInvocationFlow,
) {
    private val body = StaticCallbackNode.Anonymous(flow.body)

    fun edges(): Refinement<List<StaticCallbackEdge>, StaticCallbackGraphFailure> {
        val result =
            when (val binding = flow.binding) {
                is CallbackBindingEvidence.Bound -> bound(binding.binding)
                is CallbackBindingEvidence.Direct -> direct(binding.binding)
                is CallbackBindingEvidence.DependencyContract -> dependencyContract(binding.binding)
                is CallbackBindingEvidence.Default ->
                    Refinement.Rejected(StaticCallbackGraphFailure.UnsupportedDefaultSupply)
                is CallbackBindingEvidence.Unavailable ->
                    Refinement.Rejected(StaticCallbackGraphFailure.Unavailable(binding.cause))
            }
        val edges =
            when (result) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        val target =
            when (val admitted = RelationCallableBody.Named.fromCompiler(observation.target)) {
                is Refinement.Refined -> StaticCallbackNode.Named(admitted.value)
                is Refinement.Rejected -> return Refinement.Rejected(StaticCallbackGraphFailure.NonCallableTarget)
            }
        return Refinement.Refined(
            (edges +
                    StaticCallbackEdge.BodyTarget(
                        body,
                        target,
                        observation.occurrence,
                        observation.policy,
                    ))
                .distinct()
        )
    }

    private fun bound(
        binding: CallbackArgumentBinding
    ): Refinement<List<StaticCallbackEdge>, StaticCallbackGraphFailure> {
        if (flow.scan != CallbackInvocationScan.EXHAUSTIVE)
            return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
        val owner =
            binding.invocationOwner as? RelationCallableBody.Named
                ?: return Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
        if (owner.evidence != observation.lexicalOwner || !binding.invocation.enclosing.matches(owner))
            return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        val formal =
            when (val admitted = binding.formal()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val origin = StaticCallbackNode.Formal(formal, binding)
        val edges =
            mutableListOf<StaticCallbackEdge>(
                StaticCallbackEdge.Supply(
                    StaticCallbackNode.Named(owner),
                    origin,
                    body,
                    binding,
                )
            )
        when (val graph = admitStaticCallbackForwardingGraph(binding, flow.forwarding)) {
            is Refinement.Refined -> edges += graph.value
            is Refinement.Rejected -> return graph
        }
        for (invocation in flow.invocations) {
            when (val route = route(origin, binding, invocation)) {
                is Refinement.Refined -> edges += route.value
                is Refinement.Rejected -> return route
            }
        }
        return Refinement.Refined(edges)
    }

    private fun route(
        origin: StaticCallbackNode.Formal,
        binding: CallbackArgumentBinding,
        invocation: CallbackParameterInvocation,
    ): Refinement<List<StaticCallbackEdge>, StaticCallbackGraphFailure> {
        val edges = mutableListOf<StaticCallbackEdge>()
        var source = origin
        val seen = mutableSetOf(origin)
        for (forward in invocation.forwardings) {
            val forwardingOwner =
                forward.target.invocationOwner as? RelationCallableBody.Named
                    ?: return Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
            if (!source.parameter.callable.matches(forwardingOwner))
                return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
            val target =
                when (val admitted = forward.target.formal()) {
                    is Refinement.Refined -> StaticCallbackNode.Formal(admitted.value, binding)
                    is Refinement.Rejected -> return admitted
                }
            if (!seen.add(target) && flow.forwarding == CallbackForwardingEvidence.InvocationRoutes)
                return Refinement.Rejected(StaticCallbackGraphFailure.CyclicRoute)
            edges += StaticCallbackEdge.Forward(source, target, forward)
            source = target
        }
        val invokingOwner =
            invocation.owner as? RelationCallableBody.Named
                ?: return Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
        if (!source.parameter.callable.matches(invokingOwner))
            return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        edges += StaticCallbackEdge.Invoke(source, body, StaticCallbackNode.Named(invokingOwner), invocation)
        return Refinement.Refined(edges)
    }

    private fun dependencyContract(
        binding: CallbackDependencyContract
    ): Refinement<List<StaticCallbackEdge>, StaticCallbackGraphFailure> {
        if (flow.scan != CallbackInvocationScan.EXHAUSTIVE)
            return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
        val owner =
            binding.owner as? RelationCallableBody.Named
                ?: return Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
        if (owner.evidence != observation.lexicalOwner)
            return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        return Refinement.Refined(
            listOf(StaticCallbackEdge.DependencyContractInvoke(StaticCallbackNode.Named(owner), body, binding))
        )
    }

    private fun direct(
        binding: CallbackDirectInvocationBinding
    ): Refinement<List<StaticCallbackEdge>, StaticCallbackGraphFailure> {
        if (flow.scan != CallbackInvocationScan.NOT_APPLICABLE)
            return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
        val owner =
            binding.owner as? RelationCallableBody.Named
                ?: return Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
        if (owner.evidence != observation.lexicalOwner)
            return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        return Refinement.Refined(
            listOf(StaticCallbackEdge.DirectInvoke(StaticCallbackNode.Named(owner), body, binding))
        )
    }
}

private fun CallbackArgumentBinding.formal(): Refinement<CallbackParameterIdentity, StaticCallbackGraphFailure> =
    when (val admitted = CallbackParameterIdentity.fromCompiler(invocation.callable, position, parameter)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected ->
            Refinement.Rejected(
                StaticCallbackGraphFailure.InvalidFlow(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
            )
    }

private fun RelationEndpoint.matches(owner: RelationCallableBody.Named): Boolean =
    file == owner.file && range == owner.range && compilerIdentity == owner.compilerIdentity

private fun StaticCallbackNode.insideWorkspace(root: CanonicalWorkspaceRoot): Boolean {
    val file =
        when (this) {
            is StaticCallbackNode.Named -> callable.file
            is StaticCallbackNode.Anonymous -> body.file
            is StaticCallbackNode.Formal -> parameter.callable.file
        }
    if (file !is SymbolDiscoveryFileIdentity.Workspace) return false
    return CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of(file.path.value)) is Refinement.Refined
}

private fun List<StaticCallbackEdge>.nodes(): Set<StaticCallbackNode> = flatMap { edge ->
    listOf(edge.source, edge.target) +
        when (edge) {
            is StaticCallbackEdge.ImmutableUses ->
                edge.evidence.uses.flatMap { use ->
                    when (use) {
                        is ImmutableCallbackInvocationUse.Supplied ->
                            listOf(StaticCallbackNode.Formal(use.summary.formal, use.supplier.binding)) +
                                use.summary.invocations.mapNotNull {
                                    (it.owner as? RelationCallableBody.Named)?.let(StaticCallbackNode::Named)
                                }
                        is ImmutableCallbackInvocationUse.Direct ->
                            listOf(StaticCallbackNode.Named(use.binding.owner as RelationCallableBody.Named))
                        is ImmutableCallbackInvocationUse.Unused -> emptyList()
                    }
                }
            is StaticCallbackEdge.Supply -> listOf(edge.body)
            is StaticCallbackEdge.Invoke -> listOf(edge.owner)
            is StaticCallbackEdge.DependencyContractInvoke,
            is StaticCallbackEdge.BodyTarget,
            is StaticCallbackEdge.DirectInvoke,
            is StaticCallbackEdge.Forward -> emptyList()
        }
}
    .toSet()
