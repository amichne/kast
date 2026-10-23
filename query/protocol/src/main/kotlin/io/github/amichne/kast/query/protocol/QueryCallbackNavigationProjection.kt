package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationNodeDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationStepDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackOwnerExecution
import io.github.amichne.kast.relation.contract.CompleteCallbackFlowGraph
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.StaticCallbackEdge
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.StaticCallbackNode
import io.github.amichne.kast.traversal.contract.CallbackTraversal
import io.github.amichne.kast.traversal.contract.CallbackTraversalStep

/** Navigation cannot add authority: its only input graph has passed the existing callback admission. */
internal fun RelationCallbackObservation.projectNavigation(
    projection: CallbackProjection
): QueryCallbackNavigationDocument? =
    when (val admitted = CompleteStaticCallbackGraph.admit(this)) {
        is Refinement.Rejected ->
            when (val cause = admitted.failure.protocolGraphCause()) {
                is Refinement.Refined ->
                    provenOwnerPrefix(projection)?.let { QueryCallbackNavigationDocument.Unproven(cause.value, it) }
                is Refinement.Rejected ->
                    provenOwnerPrefix(projection)?.let {
                        QueryCallbackNavigationDocument.Unproven(
                            QueryCallbackGraphCauseDocument.InvalidGraphFailure(cause.failure),
                            it,
                        )
                    }
            }
        is Refinement.Refined -> CallbackTraversal.walk(admitted.value).projectNavigation(projection)
    }

private fun List<CallbackTraversalStep>.projectSteps(
    projection: CallbackProjection
): BoundedProtocolList<QueryCallbackNavigationStepDocument>? {
    val projected = map { it.projectStep(projection) ?: return null }
    return BoundedProtocolList.create(projected).navigationValue()
}

private fun CallbackTraversalStep.projectStep(projection: CallbackProjection): QueryCallbackNavigationStepDocument? {
    val source = projection.navigationNode(edge.source) ?: return null
    val target = projection.navigationNode(edge.target) ?: return null
    val evidence = projection.navigationEvidence(edge) ?: return null
    val depth = TraversalDepthDocument.parse(depth.value).navigationValue() ?: return null
    return QueryCallbackNavigationStepDocument(depth, source, target, evidence)
}

private fun CallbackTraversal.projectNavigation(projection: CallbackProjection): QueryCallbackNavigationDocument? =
    steps.projectSteps(projection)?.let { steps ->
        if (unsupported.isEmpty() && frontier.isEmpty()) QueryCallbackNavigationDocument.Proven(steps)
        else
            QueryCallbackNavigationDocument.Unproven(
                QueryCallbackGraphCauseDocument.Unavailable(QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY),
                steps,
            )
    }

/** A failed inner proof does not erase independently admitted enclosing execution routes. */
private fun RelationCallbackObservation.provenOwnerPrefix(
    projection: CallbackProjection
): BoundedProtocolList<QueryCallbackNavigationStepDocument>? {
    val pending = ArrayDeque<CallbackOwnerExecution>()
    when (val flow = flow) {
        is CallbackInvocationFlowRead.Observed -> flow.flow.ownerBindings.forEach { pending.add(it.execution) }
        is CallbackInvocationFlowRead.Immutable -> flow.flow.ownerBindings.forEach { pending.add(it.execution) }
        is CallbackInvocationFlowRead.Unavailable,
        is CallbackInvocationFlowRead.ContractRejected -> Unit
    }
    val visited = java.util.IdentityHashMap<CallbackOwnerExecution, Unit>()
    val steps = mutableListOf<CallbackTraversalStep>()
    while (pending.isNotEmpty()) {
        val owner = pending.removeFirst()
        if (visited.put(owner, Unit) == null) {
            pending.addAll(owner.enclosingExecutions())
            steps.addAll(owner.provenSteps(lexicalOwner))
        }
    }
    return steps.distinctBy { it.edge }.sortedBy { it.depth }.projectSteps(projection)
}

private fun CallbackOwnerExecution.provenSteps(
    root: io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
): List<CallbackTraversalStep> =
    when (val proof = admittedOwnerGraph()) {
        is Refinement.Refined ->
            if (proof.value.root == root) CallbackTraversal.walk(proof.value).steps else emptyList()
        is Refinement.Rejected -> emptyList()
    }

private fun CallbackOwnerExecution.enclosingExecutions(): List<CallbackOwnerExecution> =
    when (this) {
        is CallbackOwnerExecution.Observed -> flow.ownerBindings.map { it.execution }
        is CallbackOwnerExecution.Immutable -> flow.ownerBindings.map { it.execution }
        CallbackOwnerExecution.Unproven -> emptyList()
    }

private fun CallbackOwnerExecution.admittedOwnerGraph():
    Refinement<CompleteCallbackFlowGraph, StaticCallbackGraphFailure> =
    when (this) {
        is CallbackOwnerExecution.Observed -> CompleteCallbackFlowGraph.admit(flow)
        is CallbackOwnerExecution.Immutable -> CompleteCallbackFlowGraph.admit(flow, owner)
        CallbackOwnerExecution.Unproven -> Refinement.Rejected(StaticCallbackGraphFailure.MissingNamedOwner)
    }

private fun CallbackProjection.navigationNode(node: StaticCallbackNode): QueryCallbackNavigationNodeDocument? =
    when (node) {
        is StaticCallbackNode.Named ->
            callable(node.callable.evidence)?.let {
                QueryCallbackNavigationNodeDocument.Callable(QueryCallbackBodyDocument.Named(it))
            }
        is StaticCallbackNode.Anonymous -> anonymous(node.body)?.let(QueryCallbackNavigationNodeDocument::Callable)
        is StaticCallbackNode.Formal -> {
            val parameter = parameter(node.parameter)
            val supplier = bound(node.supply)
            if (parameter == null || supplier == null) null
            else QueryCallbackNavigationNodeDocument.Formal(parameter, supplier)
        }
        is StaticCallbackNode.ClassReference,
        is StaticCallbackNode.DeferredSupplier -> null
    }

private fun CallbackProjection.navigationEvidence(edge: StaticCallbackEdge): QueryCallbackNavigationEvidenceDocument? =
    when (edge) {
        is StaticCallbackEdge.Supply -> {
            val binding = bound(edge.binding)
            val body = anonymous(edge.body.body)
            if (binding == null || body == null) null else QueryCallbackNavigationEvidenceDocument.Supply(binding, body)
        }
        is StaticCallbackEdge.Forward ->
            forwarding(edge.evidence)?.let(QueryCallbackNavigationEvidenceDocument::Forward)
        is StaticCallbackEdge.Invoke -> invocation(edge.evidence)?.let(QueryCallbackNavigationEvidenceDocument::Invoke)
        is StaticCallbackEdge.DirectInvoke ->
            direct(edge.evidence)?.let(QueryCallbackNavigationEvidenceDocument::DirectInvoke)
        is StaticCallbackEdge.NestedDirectInvoke ->
            direct(edge.evidence)?.let(QueryCallbackNavigationEvidenceDocument::DirectInvoke)
        is StaticCallbackEdge.BodyTarget -> {
            val occurrence = occurrence(edge.occurrence)
            val policy = edge.namedCallPolicy.projectPolicy(this)
            if (occurrence == null || policy == null) null
            else QueryCallbackNavigationEvidenceDocument.FunctionInvocation(occurrence, policy)
        }
        is StaticCallbackEdge.ImmutableUses,
        is StaticCallbackEdge.DependencyContractInvoke,
        is StaticCallbackEdge.DependencyDeferredSupply,
        is StaticCallbackEdge.BodyClassReference -> null
    }

private fun <V, F> Refinement<V, F>.navigationValue(): V? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
