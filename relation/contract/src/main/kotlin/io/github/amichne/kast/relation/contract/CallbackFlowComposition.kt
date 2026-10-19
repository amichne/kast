package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.util.IdentityHashMap

internal class CallbackFlowComposition {
    val edges = mutableListOf<StaticCallbackEdge>()
    private val active = mutableSetOf<RelationCallableBody.Anonymous>()
    private val completed = IdentityHashMap<CallbackInvocationFlow, CompilerGroundedSymbolEvidence>()
    private val immutableCompleted = IdentityHashMap<ImmutableCallbackInvocationFlow, Unit>()

    fun appendImmutable(flow: ImmutableCallbackInvocationFlow): Refinement<Unit, StaticCallbackGraphFailure> {
        if (immutableCompleted.containsKey(flow)) return Refinement.Refined(Unit)
        if (flow.obligations.any { it != CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION })
            return Refinement.Rejected(StaticCallbackGraphFailure.ImmutableUnresolved(flow))
        if (flow.scan != CallbackInvocationScan.EXHAUSTIVE)
            return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
        val origin =
            flow.origin as? ImmutableCallbackValueOrigin.Anonymous
                ?: return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        if (active.size >= MAX_OWNER_DEPTH)
            return Refinement.Rejected(
                StaticCallbackGraphFailure.Unavailable(CallbackInvocationFlowCause.WORK_LIMIT_REACHED)
            )
        if (!active.add(origin.body)) return Refinement.Rejected(StaticCallbackGraphFailure.CyclicRoute)
        val result = immutableUses(flow, origin.body)
        active.remove(origin.body)
        if (result is Refinement.Refined) immutableCompleted[flow] = Unit
        return result
    }

    private fun immutableUses(
        flow: ImmutableCallbackInvocationFlow,
        body: RelationCallableBody.Anonymous,
    ): Refinement<Unit, StaticCallbackGraphFailure> {
        for (use in flow.uses) {
            when (val admitted = immutableUse(flow, body, use)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun immutableUse(
        flow: ImmutableCallbackInvocationFlow,
        body: RelationCallableBody.Anonymous,
        use: ImmutableCallbackInvocationUse,
    ): Refinement<Unit, StaticCallbackGraphFailure> {
        val actualOwner = use.executionOwner() ?: return Refinement.Refined(Unit)
        val expected =
            when (use) {
                is ImmutableCallbackInvocationUse.Direct -> use.value.destination.enclosing
                is ImmutableCallbackInvocationUse.Supplied -> use.supplier.binding.invocation.enclosing
                is ImmutableCallbackInvocationUse.Unused -> return Refinement.Refined(Unit)
            }
        when (val root = owner(flow.basis, flow.ownerBindings, actualOwner)) {
            is Refinement.Rejected -> return root
            is Refinement.Refined ->
                if (!expected.matchesCallbackRoot(root.value))
                    return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        }
        if (use is ImmutableCallbackInvocationUse.Supplied) {
            val admitted = immutableSuppliedRoutes(flow, body, use)
            if (admitted is Refinement.Rejected) return admitted
        }
        edges +=
            StaticCallbackEdge.ImmutableUses(actualOwner.callbackNode(), StaticCallbackNode.Anonymous(body), flow, use)
        return Refinement.Refined(Unit)
    }

    private fun immutableSuppliedRoutes(
        flow: ImmutableCallbackInvocationFlow,
        body: RelationCallableBody.Anonymous,
        use: ImmutableCallbackInvocationUse.Supplied,
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> {
        val owners =
            use.summary.ownerBindings + flow.ownerBindings.filter { it.body == use.supplier.binding.invocationOwner }
        if (owners.groupBy { it.body }.values.any { it.distinct().size != 1 })
            return Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        val target =
            if (use.value.origin == flow.origin) CallbackRouteTarget.Anonymous(body)
            else CallbackRouteTarget.RetainedTransport
        val context = CallbackRouteContext(flow.basis, target, owners.distinctBy { it.body }, use.summary.forwarding)
        return routes(context, use.supplier.binding, use.summary.invocations)
    }

    fun append(flow: CallbackInvocationFlow): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> {
        completed[flow]?.let {
            return Refinement.Refined(it)
        }
        if (flow.obligations.any { it != CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION })
            return Refinement.Rejected(StaticCallbackGraphFailure.Unresolved(flow))
        if (active.size >= MAX_OWNER_DEPTH)
            return Refinement.Rejected(
                StaticCallbackGraphFailure.Unavailable(CallbackInvocationFlowCause.WORK_LIMIT_REACHED)
            )
        if (!active.add(flow.body)) return Refinement.Rejected(StaticCallbackGraphFailure.CyclicRoute)
        val result = appendBinding(flow)
        active.remove(flow.body)
        if (result is Refinement.Refined) completed[flow] = result.value
        return result
    }

    private fun appendBinding(
        flow: CallbackInvocationFlow
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> =
        when (val binding = flow.binding) {
            is CallbackBindingEvidence.Bound -> bound(flow, binding.binding)
            is CallbackBindingEvidence.Direct -> direct(flow, binding.binding)
            is CallbackBindingEvidence.DependencyContract -> dependency(flow, binding.binding)
            is CallbackBindingEvidence.DeferredDependency -> deferred(flow, binding.binding)
            is CallbackBindingEvidence.Default ->
                Refinement.Rejected(StaticCallbackGraphFailure.UnsupportedDefaultSupply)
            is CallbackBindingEvidence.Unavailable ->
                Refinement.Rejected(StaticCallbackGraphFailure.Unavailable(binding.cause))
        }

    private fun owner(
        flow: CallbackInvocationFlow,
        owner: RelationCallableBody,
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> =
        owner(flow.basis, flow.ownerBindings, owner)

    fun owner(
        basis: SemanticReadIdentity,
        bindings: List<CallbackBodyBinding>,
        owner: RelationCallableBody,
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> =
        when (owner) {
            is RelationCallableBody.Named -> Refinement.Refined(owner.evidence)
            is RelationCallableBody.Anonymous -> ownerExecution(basis, bindings.singleOrNull { it.body == owner })
        }

    private fun ownerExecution(
        basis: SemanticReadIdentity,
        binding: CallbackBodyBinding?,
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> =
        when (val execution = binding?.execution) {
            null,
            CallbackOwnerExecution.Unproven -> Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
            is CallbackOwnerExecution.Observed ->
                if (execution.flow.basis != basis)
                    Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
                else append(execution.flow)
            is CallbackOwnerExecution.Immutable -> immutableOwner(basis, execution)
        }

    private fun immutableOwner(
        basis: SemanticReadIdentity,
        execution: CallbackOwnerExecution.Immutable,
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> =
        if (execution.flow.basis != basis) Refinement.Rejected(StaticCallbackGraphFailure.SupplierIdentityMismatch)
        else
            when (val graph = appendImmutable(execution.flow)) {
                is Refinement.Rejected -> graph
                is Refinement.Refined -> Refinement.Refined(execution.owner.evidence)
            }

    private fun bound(
        flow: CallbackInvocationFlow,
        binding: CallbackArgumentBinding,
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> {
        if (flow.scan != CallbackInvocationScan.EXHAUSTIVE)
            return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
        return routes(
            CallbackRouteContext(
                flow.basis,
                CallbackRouteTarget.Anonymous(flow.body),
                flow.ownerBindings,
                flow.forwarding,
            ),
            binding,
            flow.invocations,
        )
    }

    private fun direct(
        flow: CallbackInvocationFlow,
        binding: CallbackDirectInvocationBinding,
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> {
        if (flow.scan != CallbackInvocationScan.NOT_APPLICABLE)
            return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
        val root = owner(flow, binding.owner)
        if (root is Refinement.Refined)
            edges +=
                StaticCallbackEdge.DirectInvoke(
                    binding.owner.callbackNode(),
                    StaticCallbackNode.Anonymous(flow.body),
                    binding,
                )
        return root
    }

    private fun dependency(
        flow: CallbackInvocationFlow,
        binding: CallbackDependencyContract,
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> {
        if (flow.scan != CallbackInvocationScan.EXHAUSTIVE)
            return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
        val root = owner(flow, binding.owner)
        if (root is Refinement.Refined)
            edges +=
                StaticCallbackEdge.DependencyContractInvoke(
                    binding.owner.callbackNode(),
                    StaticCallbackNode.Anonymous(flow.body),
                    binding,
                )
        return root
    }

    private fun deferred(
        flow: CallbackInvocationFlow,
        binding: CallbackDeferredDependency,
    ): Refinement<CompilerGroundedSymbolEvidence, StaticCallbackGraphFailure> {
        if (flow.scan != CallbackInvocationScan.EXHAUSTIVE)
            return Refinement.Rejected(StaticCallbackGraphFailure.IncompleteScan)
        edges += deferredExecutionEdges(binding.owner.execution)
        edges +=
            StaticCallbackEdge.DependencyDeferredSupply(
                StaticCallbackNode.DeferredSupplier(binding.owner),
                StaticCallbackNode.Anonymous(flow.body),
                binding,
            )
        return Refinement.Refined(binding.owner.evidence)
    }
}

private const val MAX_OWNER_DEPTH = 64
