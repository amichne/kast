package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun QueryCallbackObservationDocument.admit(): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (relation != RelationKindDocument.CALLERS && relation != RelationKindDocument.CALLEES)
        return rejected(QueryCallbackDocumentFailure.MEANING_MISMATCH)
    if (!target.validDeclaration() || !lexicalOwner.validDeclaration())
        return rejected(QueryCallbackDocumentFailure.CALLABLE_DECLARATION_MISMATCH)
    if (!callbackBody.contains(occurrence) || !lexicalOwner.declaration.contains(callbackBody))
        return rejected(QueryCallbackDocumentFailure.CALLBACK_CONTAINMENT_MISMATCH)
    val policy = namedPolicy
    if (
        policy is QueryCallbackNamedPolicyDocument.Excluded &&
            (!policy.excludedBoundary.contains(callbackBody) ||
                !lexicalOwner.declaration.contains(policy.excludedBoundary))
    )
        return rejected(QueryCallbackDocumentFailure.EXCLUDED_BOUNDARY_MISMATCH)
    return when (val flow = flow) {
        is QueryCallbackFlowDocument.Unavailable -> Refinement.Refined(Unit)
        is QueryCallbackFlowDocument.ContractRejected -> Refinement.Refined(Unit)
        is QueryCallbackFlowDocument.Observed -> admitFlow(flow)
    }
}

private fun QueryCallbackObservationDocument.admitFlow(
    flow: QueryCallbackFlowDocument.Observed
): Refinement<Unit, QueryCallbackDocumentFailure> =
    when (val owners = flow.admitOwnerBindings()) {
        is Refinement.Rejected -> owners
        is Refinement.Refined -> admitObservedFlow(flow)
    }

private fun QueryCallbackObservationDocument.admitObservedFlow(
    flow: QueryCallbackFlowDocument.Observed
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (!flow.body.validBody()) return rejected(QueryCallbackDocumentFailure.ANONYMOUS_IDENTITY_MISMATCH)
    if (!flow.body.occurrence.sameSite(callbackBody)) return rejected(QueryCallbackDocumentFailure.FLOW_BODY_MISMATCH)
    if (flow.invocations.values.distinct().size != flow.invocations.values.size)
        return rejected(QueryCallbackDocumentFailure.DUPLICATE_INVOCATION)
    if (
        flow.obligations.values.distinct().size != flow.obligations.values.size ||
            flow.invocations.values.isEmpty() && flow.obligations.values.isEmpty()
    )
        return rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    return when (val binding = flow.binding) {
        is QueryCallbackBindingDocument.Unavailable -> binding.admitUnavailable(flow)
        is QueryCallbackBindingDocument.Bound ->
            when (val admitted = admitBinding(flow, binding)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined -> admitInvocations(flow, binding)
            }
    }
}

private fun QueryCallbackBindingDocument.Unavailable.admitUnavailable(
    flow: QueryCallbackFlowDocument.Observed
): Refinement<Unit, QueryCallbackDocumentFailure> =
    if (flow.invocations.values.isNotEmpty() || cause !in flow.obligations.values)
        rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    else Refinement.Refined(Unit)

private fun QueryCallbackObservationDocument.admitBinding(
    flow: QueryCallbackFlowDocument.Observed,
    bound: QueryCallbackBindingDocument.Bound,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (
        bound.invocationOwner is QueryCallbackBodyDocument.Anonymous &&
            QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION !in flow.obligations.values
    )
        return rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    val supplyingOwner =
        when (val owner = bound.invocationOwner.admitOwner()) {
            is Refinement.Rejected -> return owner
            is Refinement.Refined -> owner.value
        }
    if (!supplyingOwner.contains(bound.invocationOccurrence) || !lexicalOwner.declaration.contains(supplyingOwner))
        return rejected(QueryCallbackDocumentFailure.INVOCATION_OWNER_MISMATCH)
    val signature =
        bound.callable.compilerTarget.compilerEvidence.signature as? CompilerSignatureDocument.Function
            ?: return rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    if (!bound.validCallableMapping(flow.basis) || !validBindingSites(bound))
        return rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    if (bound.position.value !in signature.valueParameters.values.indices)
        return rejected(QueryCallbackDocumentFailure.PARAMETER_POSITION_MISMATCH)
    return Refinement.Refined(Unit)
}

internal fun QueryCallbackBindingDocument.Bound.validCallableMapping(basis: ImpactSemanticBasisDocument): Boolean {
    val target = callable.compilerTarget
    val invocationCallable = invocation.callable
    return callable.validCallable() &&
        invocationCallable.basis == basis &&
        invocationCallable.file == target.file &&
        invocationCallable.compilerIdentity == target.compilerEvidence.identity &&
        invocationCallable.range.start == target.range.startInclusive &&
        invocationCallable.range.end == target.range.endExclusive
}

private fun QueryCallbackObservationDocument.validBindingSites(bound: QueryCallbackBindingDocument.Bound): Boolean =
    bound.invocation.range.start == bound.invocationOccurrence.range.startInclusive &&
        bound.invocation.range.end == bound.invocationOccurrence.range.endExclusive &&
        bound.invocationOccurrence.contains(callbackBody) &&
        lexicalOwner.declaration.contains(bound.invocationOccurrence) &&
        bound.callable.declaration.contains(bound.parameter)

internal fun QueryCallbackBodyDocument.admitOwner():
    Refinement<RelationOccurrenceDocument, QueryCallbackDocumentFailure> =
    when (this) {
        is QueryCallbackBodyDocument.Named ->
            if (callable.validCallable()) Refinement.Refined(callable.declaration)
            else rejected(QueryCallbackDocumentFailure.INVOCATION_OWNER_MISMATCH)
        is QueryCallbackBodyDocument.Anonymous ->
            if (validBody()) Refinement.Refined(occurrence)
            else rejected(QueryCallbackDocumentFailure.ANONYMOUS_IDENTITY_MISMATCH)
    }

private fun admitInvocations(
    flow: QueryCallbackFlowDocument.Observed,
    bound: QueryCallbackBindingDocument.Bound,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    for (invocation in flow.invocations.values) {
        val owner =
            when (val admitted = invocation.owner.admitOwner()) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> admitted.value
            }
        if (
            !invocation.owner.isReceivingCallable(bound) &&
                QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION !in flow.obligations.values
        )
            return rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
        if (
            !bound.callable.declaration.contains(invocation.occurrence) ||
                !bound.callable.declaration.contains(owner) ||
                !owner.contains(invocation.occurrence)
        )
            return rejected(QueryCallbackDocumentFailure.INVOCATION_OWNER_MISMATCH)
        if (!invocation.validTransfers(bound)) return rejected(QueryCallbackDocumentFailure.TRANSFER_PROOF_MISMATCH)
    }
    return Refinement.Refined(Unit)
}

private fun QueryCallbackBodyDocument.isReceivingCallable(bound: QueryCallbackBindingDocument.Bound): Boolean =
    when (this) {
        is QueryCallbackBodyDocument.Named ->
            callable.compilerTarget.compilerEvidence.identity == bound.callable.compilerTarget.compilerEvidence.identity
        is QueryCallbackBodyDocument.Anonymous -> false
    }

private fun QueryCallbackInvocationDocument.validTransfers(bound: QueryCallbackBindingDocument.Bound): Boolean {
    val transfers = callableTransfers.values
    if (transfers.any { !it.validTransfer(bound.invocation.callable) }) return false
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

private fun QueryCallbackCallableDocument.validDeclaration(): Boolean =
    declaration.file == compilerTarget.file &&
        declaration.range == compilerTarget.range &&
        compilerTarget.compilerEvidence.signature.supports(compilerTarget.kind)

private fun QueryCallbackCallableDocument.validCallable(): Boolean =
    validDeclaration() && compilerTarget.compilerEvidence.signature is CompilerSignatureDocument.Function

internal fun QueryCallbackBodyDocument.Anonymous.validBody(): Boolean {
    val signature = compilerEvidence.signature as? CompilerSignatureDocument.Function ?: return false
    return signature.qualifiedIdentity.value ==
        "anonymous@${occurrence.file.value}#${occurrence.range.startInclusive.value}:${occurrence.range.endExclusive.value}"
}

internal fun RelationOccurrenceDocument.contains(other: RelationOccurrenceDocument): Boolean =
    file == other.file &&
        range.startInclusive.value <= other.range.startInclusive.value &&
        range.endExclusive.value >= other.range.endExclusive.value

internal fun RelationOccurrenceDocument.sameSite(other: RelationOccurrenceDocument): Boolean =
    file == other.file && range == other.range

private fun rejected(failure: QueryCallbackDocumentFailure): Refinement.Rejected<QueryCallbackDocumentFailure> =
    Refinement.Rejected(failure)

private fun ImpactValueSiteReferenceDocument.validSite(): Boolean =
    range.start.value < range.end.value &&
        enclosing.range.start.value < enclosing.range.end.value &&
        range.start.value >= enclosing.range.start.value &&
        range.end.value <= enclosing.range.end.value
