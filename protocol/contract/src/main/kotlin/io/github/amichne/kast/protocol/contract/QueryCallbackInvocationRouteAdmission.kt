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
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val receiver = CallbackInvocationReceiver(initial, initialReference)
    for (invocation in invocations.values) {
        val terminal =
            when (val route = admitForwardings(invocation, receiver)) {
                is Refinement.Rejected -> return route
                is Refinement.Refined -> route.value
            }
        when (val admitted = invocation.admitReceivingOwner(terminal, obligations.values)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
    }
    return Refinement.Refined(Unit)
}

private fun QueryCallbackFlowDocument.Observed.admitForwardings(
    invocation: QueryCallbackInvocationDocument,
    initial: CallbackInvocationReceiver,
): Refinement<CallbackInvocationReceiver, QueryCallbackDocumentFailure> {
    var receiver = initial
    for (forwarding in invocation.forwardings.values) {
        when (val admitted = forwarding.admitForwarding(receiver, basis, obligations.values)) {
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

private fun QueryCallbackParameterIdentityDocument.sameParameter(
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
    val transfers = callableTransfers.values
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
