package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** The admitted formal and matching declaration reference advance together at every forwarding hop. */
private data class CallbackInvocationReceiver(
    val parameter: QueryCallbackParameterIdentityDocument,
    val reference: ImpactDeclarationReferenceDocument,
)

internal fun QueryCallbackFlowDocument.Observed.admitParameterInvocations(
    initial: QueryCallbackParameterIdentityDocument,
    initialReference: ImpactDeclarationReferenceDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> =
    CallbackInvocationRouteProof(basis, invocations.values, obligations.values, forwarding)
        .admitParameterInvocations(initial, initialReference)

internal class CallbackInvocationRouteProof(
    val basis: ImpactSemanticBasisDocument,
    val invocations: List<QueryCallbackInvocationDocument>,
    val obligations: List<QueryCallbackFlowCauseDocument>,
    val forwarding: QueryCallbackForwardingEvidenceDocument,
)

internal fun CallbackInvocationRouteProof.admitParameterInvocations(
    initial: QueryCallbackParameterIdentityDocument,
    initialReference: ImpactDeclarationReferenceDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val receiver = CallbackInvocationReceiver(initial, initialReference)
    when (val graph = admitForwardingEvidence(initial)) {
        is Refinement.Rejected -> return graph
        is Refinement.Refined -> Unit
    }
    for (invocation in invocations) {
        val terminal =
            when (val route = admitForwardings(invocation, receiver)) {
                is Refinement.Rejected -> return route
                is Refinement.Refined -> route.value
            }
        when (val admitted = invocation.admitReceivingOwner(terminal, obligations)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
    }
    return Refinement.Refined(Unit)
}

internal data class CallbackFormalSite(
    val callable: QueryExcludedCompilerTargetDocument,
    val declaration: SourceRangeDocument,
    val parameterFile: ProtocolText,
    val parameter: SourceRangeDocument,
    val position: ProtocolOffset,
)

internal fun QueryCallbackParameterIdentityDocument.formalSite() =
    CallbackFormalSite(callable.compilerTarget, callable.declaration.range, parameter.file, parameter.range, position)

private fun CallbackInvocationRouteProof.admitForwardingEvidence(
    initial: QueryCallbackParameterIdentityDocument
): Refinement<Unit, QueryCallbackDocumentFailure> =
    when (val evidence = forwarding) {
        QueryCallbackForwardingEvidenceDocument.InvocationRoutes -> Refinement.Refined(Unit)
        is QueryCallbackForwardingEvidenceDocument.ExhaustedGraph -> admitExhaustedGraph(initial, evidence)
    }

private fun CallbackInvocationRouteProof.admitExhaustedGraph(
    initial: QueryCallbackParameterIdentityDocument,
    graph: QueryCallbackForwardingEvidenceDocument.ExhaustedGraph,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val inventory = graph.formals.values.map { it.formalSite() }.toSet()
    if (!initial.sameParameter(graph.root)) return rejected(QueryCallbackDocumentFailure.INVALID_FORWARDING_PATH)
    when (val formals = graph.admitFormalInventory(inventory)) {
        is Refinement.Rejected -> return formals
        is Refinement.Refined -> Unit
    }
    when (val edges = admitGraphForwardings(graph.forwardings.values, inventory)) {
        is Refinement.Rejected -> return edges
        is Refinement.Refined -> Unit
    }
    if (graph.reachableFormalSites() != inventory) return rejected(QueryCallbackDocumentFailure.INVALID_FORWARDING_PATH)
    if (invocations.any { !it.hasInventoriedForwardings(graph.forwardings.values) })
        return rejected(QueryCallbackDocumentFailure.INVALID_FORWARDING_PATH)
    return Refinement.Refined(Unit)
}

private fun QueryCallbackForwardingEvidenceDocument.ExhaustedGraph.admitFormalInventory(
    inventory: Set<CallbackFormalSite>
): Refinement<Unit, QueryCallbackDocumentFailure> =
    if (
        formals.values.any { !it.validParameter() } ||
            inventory.size != formals.values.size ||
            root.formalSite() !in inventory
    )
        rejected(QueryCallbackDocumentFailure.INVALID_FORWARDING_PATH)
    else Refinement.Refined(Unit)

private fun CallbackInvocationRouteProof.admitGraphForwardings(
    edges: List<QueryCallbackForwardingDocument>,
    inventory: Set<CallbackFormalSite>,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    for ((index, edge) in edges.withIndex()) {
        if (
            edge.source.formalSite() !in inventory ||
                edge.target.parameterIdentity().formalSite() !in inventory ||
                edges.subList(0, index).any { it.sameForwarding(edge) }
        )
            return rejected(QueryCallbackDocumentFailure.INVALID_FORWARDING_PATH)
        when (
            val admitted =
                edge.admitForwarding(
                    CallbackInvocationReceiver(edge.source, edge.source.callable.reference(basis)),
                    basis,
                    obligations,
                )
        ) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
    }
    return Refinement.Refined(Unit)
}

private fun QueryCallbackForwardingEvidenceDocument.ExhaustedGraph.reachableFormalSites(): Set<CallbackFormalSite> {
    val outgoing = forwardings.values.groupBy { it.source.formalSite() }
    val reachable = mutableSetOf<CallbackFormalSite>()
    val pending = ArrayDeque<CallbackFormalSite>()
    pending.add(root.formalSite())
    while (pending.isNotEmpty()) {
        val source = pending.removeFirst()
        if (!reachable.add(source)) continue
        for (edge in outgoing[source].orEmpty()) pending.add(edge.target.parameterIdentity().formalSite())
    }
    return reachable
}

private fun QueryCallbackInvocationDocument.hasInventoriedForwardings(
    edges: List<QueryCallbackForwardingDocument>
): Boolean = forwardings.values.all { path -> edges.any { it.sameForwarding(path) } }

private fun QueryCallbackForwardingDocument.sameForwarding(other: QueryCallbackForwardingDocument): Boolean =
    source.sameParameter(other.source) &&
        argument.sameSite(other.argument) &&
        target.invocation == other.target.invocation &&
        target.invocationOccurrence.sameSite(other.target.invocationOccurrence) &&
        target.invocationOwner.sameOwner(other.target.invocationOwner) &&
        target.parameterIdentity().sameParameter(other.target.parameterIdentity()) &&
        callableTransfers.values == other.callableTransfers.values

private fun QueryCallbackBodyDocument.sameOwner(other: QueryCallbackBodyDocument): Boolean =
    when (this) {
        is QueryCallbackBodyDocument.Named ->
            other is QueryCallbackBodyDocument.Named &&
                callable.compilerTarget == other.callable.compilerTarget &&
                callable.declaration.sameSite(other.callable.declaration)
        is QueryCallbackBodyDocument.Anonymous ->
            other is QueryCallbackBodyDocument.Anonymous &&
                occurrence.sameSite(other.occurrence) &&
                compilerEvidence == other.compilerEvidence
    }

private fun CallbackInvocationRouteProof.admitForwardings(
    invocation: QueryCallbackInvocationDocument,
    initial: CallbackInvocationReceiver,
): Refinement<CallbackInvocationReceiver, QueryCallbackDocumentFailure> {
    var receiver = initial
    for (forwarding in invocation.forwardings.values) {
        when (val admitted = forwarding.admitForwarding(receiver, basis, obligations)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> receiver = admitted.value
        }
    }
    return Refinement.Refined(receiver)
}

private fun QueryCallbackForwardingDocument.admitForwarding(
    receiver: CallbackInvocationReceiver,
    basis: ImpactSemanticBasisDocument,
    obligations: List<QueryCallbackFlowCauseDocument>,
): Refinement<CallbackInvocationReceiver, QueryCallbackDocumentFailure> {
    val owner =
        when (val admitted = target.invocationOwner.admitOwner()) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    if (!source.sameParameter(receiver.parameter) || !source.validParameter())
        return rejected(QueryCallbackDocumentFailure.INVALID_FORWARDING_PATH)
    if (!validForwardingSites(receiver.parameter, owner))
        return rejected(QueryCallbackDocumentFailure.INVALID_FORWARDING_PATH)
    if (!target.validForwardingMapping(basis)) return rejected(QueryCallbackDocumentFailure.INVALID_FORWARDING_PATH)
    if (!validCallableTransfers(callableTransfers.values, receiver.reference, argument))
        return rejected(QueryCallbackDocumentFailure.TRANSFER_PROOF_MISMATCH)
    if (
        !target.invocationOwner.isReceivingCallable(source.callable) &&
            QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION !in obligations
    )
        return rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    return Refinement.Refined(CallbackInvocationReceiver(target.parameterIdentity(), target.invocation.callable))
}

private fun QueryCallbackForwardingDocument.validForwardingSites(
    parameter: QueryCallbackParameterIdentityDocument,
    owner: RelationOccurrenceDocument,
): Boolean =
    parameter.callable.declaration.contains(target.invocationOccurrence) &&
        target.invocationOccurrence.contains(argument) &&
        validForwardingOwner(parameter, owner)

private fun QueryCallbackForwardingDocument.validForwardingOwner(
    parameter: QueryCallbackParameterIdentityDocument,
    owner: RelationOccurrenceDocument,
): Boolean = owner.contains(target.invocationOccurrence) && parameter.callable.declaration.contains(owner)

private fun QueryCallbackBindingDocument.Bound.validForwardingMapping(basis: ImpactSemanticBasisDocument): Boolean =
    validCallableMapping(basis) && validInvocationSite() && parameterIdentity().validParameter()

private fun QueryCallbackInvocationDocument.admitReceivingOwner(
    receiver: CallbackInvocationReceiver,
    obligations: List<QueryCallbackFlowCauseDocument>,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val owner =
        when (val admitted = this.owner.admitOwner()) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    if (
        !this.owner.isReceivingCallable(receiver.parameter.callable) &&
            QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION !in obligations
    )
        return rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    if (!validReceivingSites(receiver.parameter.callable, owner))
        return rejected(QueryCallbackDocumentFailure.INVOCATION_OWNER_MISMATCH)
    if (!validTransfers(receiver.reference)) return rejected(QueryCallbackDocumentFailure.TRANSFER_PROOF_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun QueryCallbackInvocationDocument.validReceivingSites(
    callable: QueryCallbackCallableDocument,
    owner: RelationOccurrenceDocument,
): Boolean =
    callable.declaration.contains(occurrence) && callable.declaration.contains(owner) && owner.contains(occurrence)

internal fun QueryCallbackParameterIdentityDocument.sameParameter(
    other: QueryCallbackParameterIdentityDocument
): Boolean =
    position == other.position &&
        parameter.sameSite(other.parameter) &&
        callable.compilerTarget == other.callable.compilerTarget &&
        callable.declaration.sameSite(other.callable.declaration)

internal fun QueryCallbackBodyDocument.isReceivingCallable(receiving: QueryCallbackCallableDocument): Boolean =
    when (this) {
        is QueryCallbackBodyDocument.Named ->
            callable.compilerTarget.compilerEvidence.identity == receiving.compilerTarget.compilerEvidence.identity
        is QueryCallbackBodyDocument.Anonymous -> false
    }

private fun QueryCallbackInvocationDocument.validTransfers(callable: ImpactDeclarationReferenceDocument): Boolean {
    return validCallableTransfers(callableTransfers.values, callable, occurrence)
}

private fun validCallableTransfers(
    transfers: List<ImpactCompilerTransferDocument>,
    callable: ImpactDeclarationReferenceDocument,
    occurrence: RelationOccurrenceDocument,
): Boolean {
    if (transfers.any { !it.validTransfer(callable) }) return false
    if (transfers.zipWithNext().any { (left, right) -> left.target != right.source }) return false
    if (transfers.isEmpty()) return true
    if (
        transfers.first().source.role != ImpactValueRoleDocument.ExpressionResult ||
            transfers.last().target.role != ImpactValueRoleDocument.LocalRead
    )
        return false
    val terminal = transfers.last().target
    return terminal.enclosing.file == occurrence.file &&
        terminal.range.start.value >= occurrence.range.startInclusive.value &&
        terminal.range.end.value <= occurrence.range.endExclusive.value
}

private fun ImpactCompilerTransferDocument.validTransfer(callable: ImpactDeclarationReferenceDocument): Boolean {
    if (!validRoles() || source == target) return false
    if (source.enclosing != callable || target.enclosing != callable) return false
    return source.validSite() && target.validSite()
}

private fun ImpactCompilerTransferDocument.validRoles(): Boolean =
    when (kind) {
        ImpactTransferKindDocument.LOCAL_BINDING -> target.role == ImpactValueRoleDocument.LocalBinding
        ImpactTransferKindDocument.LOCAL_READ ->
            source.role == ImpactValueRoleDocument.LocalBinding && target.role == ImpactValueRoleDocument.LocalRead
        ImpactTransferKindDocument.ARGUMENT,
        ImpactTransferKindDocument.RETURN,
        ImpactTransferKindDocument.PROPERTY_ASSIGNMENT,
        ImpactTransferKindDocument.BRANCH_ALTERNATIVE,
        ImpactTransferKindDocument.WRAPPER_RETURN -> false
    }

private fun ImpactValueSiteReferenceDocument.validSite(): Boolean =
    range.start.value < range.end.value &&
        enclosing.range.start.value < enclosing.range.end.value &&
        range.start.value >= enclosing.range.start.value &&
        range.end.value <= enclosing.range.end.value

private fun rejected(failure: QueryCallbackDocumentFailure): Refinement.Rejected<QueryCallbackDocumentFailure> =
    Refinement.Rejected(failure)

/** Reuses the forwarding owner proof for a reverse supplier inventory edge. */
internal fun QueryCallbackForwardingDocument.admitSupplierForwarding(
    basis: ImpactSemanticBasisDocument
): Refinement<Unit, QueryCallbackDocumentFailure> =
    when (
        val admitted =
            admitForwarding(CallbackInvocationReceiver(source, source.callable.reference(basis)), basis, emptyList())
    ) {
        is Refinement.Refined -> Refinement.Refined(Unit)
        is Refinement.Rejected -> admitted
    }

internal fun QueryCallbackInvocationDocument.admitFactoryCapture(
    body: QueryCallbackBodyDocument.Anonymous,
    formal: QueryCallbackParameterIdentityDocument,
    basis: ImpactSemanticBasisDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val actual =
        owner as? QueryCallbackBodyDocument.Anonymous
            ?: return rejected(QueryCallbackDocumentFailure.INVOCATION_OWNER_MISMATCH)
    if (
        actual.compilerEvidence != body.compilerEvidence ||
            !actual.occurrence.sameSite(body.occurrence) ||
            forwardings.values.isNotEmpty()
    )
        return rejected(QueryCallbackDocumentFailure.INVOCATION_OWNER_MISMATCH)
    return admitReceivingOwner(
        CallbackInvocationReceiver(formal, formal.callable.reference(basis)),
        listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION),
    )
}

internal fun QueryCallbackInvocationDocument.admitsObservedParameter(
    parameter: QueryCallbackParameterIdentityDocument,
    occurrence: RelationOccurrenceDocument,
    body: QueryCallbackBodyDocument,
): Boolean {
    if (!this.occurrence.sameSite(occurrence) || owner != body || forwardings.values.isNotEmpty()) return false
    val first = callableTransfers.values.firstOrNull() ?: return true
    return validTransfers(parameter.callable.reference(first.source.enclosing.basis))
}
