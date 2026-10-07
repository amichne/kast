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
    when (val inventory = flow.admitInvocationInventory()) {
        is Refinement.Rejected -> return inventory
        is Refinement.Refined -> Unit
    }
    when (val scan = flow.admitScanProof()) {
        is Refinement.Rejected -> return scan
        is Refinement.Refined -> Unit
    }
    return when (val binding = flow.binding) {
        is QueryCallbackBindingDocument.Default -> admitDefaultFlow(flow, binding)
        is QueryCallbackBindingDocument.Direct -> admitDirectFlow(flow, binding)
        is QueryCallbackBindingDocument.Unavailable -> binding.admitUnavailable(flow)
        is QueryCallbackBindingDocument.Bound ->
            when (val admitted = admitBinding(flow, binding)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined -> admitInvocations(flow, binding)
            }
    }
}

private fun QueryCallbackFlowDocument.Observed.admitInvocationInventory():
    Refinement<Unit, QueryCallbackDocumentFailure> {
    if (invocations.values.distinct().size != invocations.values.size)
        return rejected(QueryCallbackDocumentFailure.DUPLICATE_INVOCATION)
    if (obligations.values.distinct().size != obligations.values.size)
        return rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    if (hasUnprovenEmptyInventory()) return rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    return Refinement.Refined(Unit)
}

private fun QueryCallbackFlowDocument.Observed.hasUnprovenEmptyInventory(): Boolean =
    invocations.values.isEmpty() &&
        obligations.values.isEmpty() &&
        scan == QueryCallbackInvocationScanDocument.INCOMPLETE

private fun QueryCallbackFlowDocument.Observed.admitScanProof(): Refinement<Unit, QueryCallbackDocumentFailure> {
    when (val forwardingScan = admitForwardingScan()) {
        is Refinement.Rejected -> return forwardingScan
        is Refinement.Refined -> Unit
    }
    return when (scan) {
        QueryCallbackInvocationScanDocument.EXHAUSTIVE ->
            if (obligations.values.any { it != QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION })
                rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
            else Refinement.Refined(Unit)
        QueryCallbackInvocationScanDocument.INCOMPLETE -> Refinement.Refined(Unit)
        QueryCallbackInvocationScanDocument.NOT_APPLICABLE ->
            if (binding !is QueryCallbackBindingDocument.Direct)
                rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
            else Refinement.Refined(Unit)
    }
}

private fun QueryCallbackFlowDocument.Observed.admitForwardingScan(): Refinement<Unit, QueryCallbackDocumentFailure> =
    when (forwarding) {
        QueryCallbackForwardingEvidenceDocument.InvocationRoutes -> Refinement.Refined(Unit)
        is QueryCallbackForwardingEvidenceDocument.ExhaustedGraph ->
            when (binding) {
                is QueryCallbackBindingDocument.Bound,
                is QueryCallbackBindingDocument.Default -> admitExhaustedGraphScan()
                is QueryCallbackBindingDocument.Direct,
                is QueryCallbackBindingDocument.Unavailable -> rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
            }
    }

private fun QueryCallbackFlowDocument.Observed.admitExhaustedGraphScan():
    Refinement<Unit, QueryCallbackDocumentFailure> =
    when (scan) {
        QueryCallbackInvocationScanDocument.EXHAUSTIVE -> Refinement.Refined(Unit)
        QueryCallbackInvocationScanDocument.INCOMPLETE ->
            if (obligations.values.isEmpty()) rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
            else Refinement.Refined(Unit)
        QueryCallbackInvocationScanDocument.NOT_APPLICABLE -> rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
    }

private fun QueryCallbackBindingDocument.Unavailable.admitUnavailable(
    flow: QueryCallbackFlowDocument.Observed
): Refinement<Unit, QueryCallbackDocumentFailure> =
    if (
        flow.invocations.values.isNotEmpty() ||
            cause !in flow.obligations.values ||
            flow.scan != QueryCallbackInvocationScanDocument.INCOMPLETE
    )
        rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    else Refinement.Refined(Unit)

private fun QueryCallbackObservationDocument.admitBinding(
    flow: QueryCallbackFlowDocument.Observed,
    bound: QueryCallbackBindingDocument.Bound,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (
        !bound.invocationOwner.isReceivingCallable(lexicalOwner) &&
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
): Refinement<Unit, QueryCallbackDocumentFailure> =
    flow.admitParameterInvocations(
        QueryCallbackParameterIdentityDocument(bound.callable, bound.position, bound.parameter),
        bound.invocation.callable,
    )

private fun admitDefaultFlow(
    flow: QueryCallbackFlowDocument.Observed,
    binding: QueryCallbackBindingDocument.Default,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (
        !binding.parameter.validParameter() ||
            !binding.parameter.parameter.contains(binding.defaultValue) ||
            !binding.defaultValue.contains(flow.body.occurrence)
    )
        return rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    return flow.admitParameterInvocations(binding.parameter, binding.parameter.callable.reference(flow.basis))
}

private fun QueryCallbackObservationDocument.admitDirectFlow(
    flow: QueryCallbackFlowDocument.Observed,
    binding: QueryCallbackBindingDocument.Direct,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val owner =
        when (val admitted = binding.owner.admitOwner()) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    if (binding.basis != flow.basis || !binding.occurrence.contains(flow.body.occurrence))
        return rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    if (!owner.contains(binding.occurrence) || !lexicalOwner.declaration.contains(owner))
        return rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    if (flow.scan != QueryCallbackInvocationScanDocument.NOT_APPLICABLE || flow.invocations.values.isNotEmpty())
        return rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
    if (
        binding.owner is QueryCallbackBodyDocument.Anonymous &&
            QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION !in flow.obligations.values
    )
        return rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    return Refinement.Refined(Unit)
}

internal fun QueryCallbackParameterIdentityDocument.validParameter(): Boolean {
    val signature =
        callable.compilerTarget.compilerEvidence.signature as? CompilerSignatureDocument.Function ?: return false
    return callable.validCallable() &&
        callable.declaration.contains(parameter) &&
        position.value in signature.valueParameters.values.indices
}

internal fun QueryCallbackBindingDocument.Bound.parameterIdentity() =
    QueryCallbackParameterIdentityDocument(callable, position, parameter)

internal fun QueryCallbackBindingDocument.Bound.validInvocationSite(): Boolean =
    invocation.range.start == invocationOccurrence.range.startInclusive &&
        invocation.range.end == invocationOccurrence.range.endExclusive

internal fun QueryCallbackCallableDocument.reference(basis: ImpactSemanticBasisDocument) =
    ImpactDeclarationReferenceDocument(
        basis,
        compilerTarget.file,
        ImpactSourceRangeDocument(declaration.range.startInclusive, declaration.range.endExclusive),
        compilerTarget.compilerEvidence.identity,
    )

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
