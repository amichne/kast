package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl

internal fun QueryCallbackFlowDocument.Observed.admitOwnerBindings(): Refinement<Unit, QueryCallbackDocumentFailure> {
    val owners =
        listOf(body) +
            invocations.values.map { it.owner } +
            listOfNotNull(
                (binding as? QueryCallbackBindingDocument.Bound)?.invocationOwner,
                (binding as? QueryCallbackBindingDocument.Direct)?.owner,
            ) +
            invocations.values.flatMap { it.forwardings.values.map { hop -> hop.target.invocationOwner } }
    if (ownerBindings.values.map { it.body }.distinct().size != ownerBindings.values.size)
        return Refinement.Rejected(QueryCallbackDocumentFailure.DUPLICATE_OWNER_BINDING)
    for (owner in ownerBindings.values) {
        if (owner.body !in owners) return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
        if (!obligations.values.containsAll(owner.obligations.values))
            return Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
        when (val admitted = owner.admitOwnerBinding(basis)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
    }
    return Refinement.Refined(Unit)
}

private fun QueryCallbackBodyBindingDocument.admitOwnerBinding(
    basis: ImpactSemanticBasisDocument
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (!body.validBody()) return Refinement.Rejected(QueryCallbackDocumentFailure.ANONYMOUS_IDENTITY_MISMATCH)
    if (
        obligations.values.distinct().size != obligations.values.size ||
            QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION !in obligations.values
    )
        return Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    if (binding is QueryCallbackBindingDocument.Unavailable && binding.cause !in obligations.values)
        return Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
    return admitSupply(basis)
}

private fun QueryCallbackBodyBindingDocument.admitSupply(
    basis: ImpactSemanticBasisDocument
): Refinement<Unit, QueryCallbackDocumentFailure> =
    when (val supplied = supply) {
        QueryCallbackBodySupplyDocument.Stored -> admitUnavailableSupply(QueryCallbackFlowCauseDocument.STORED_CALLBACK)
        QueryCallbackBodySupplyDocument.Unsupported ->
            admitUnavailableSupply(QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY)
        is QueryCallbackBodySupplyDocument.Returned -> admitReturnedSupply(supplied.occurrence)
        is QueryCallbackBodySupplyDocument.Invocation -> admitInvocationSupply(supplied, basis)
        is QueryCallbackBodySupplyDocument.DefaultParameter -> admitDefaultSupply(supplied.parameter)
        is QueryCallbackBodySupplyDocument.DirectInvocation -> admitDirectSupply(supplied.occurrence, basis)
    }

private fun QueryCallbackBodyBindingDocument.admitReturnedSupply(
    occurrence: RelationOccurrenceDocument
): Refinement<Unit, QueryCallbackDocumentFailure> =
    if (occurrence.contains(body.occurrence)) admitUnavailableSupply(QueryCallbackFlowCauseDocument.RETURNED_CALLBACK)
    else Refinement.Rejected(QueryCallbackDocumentFailure.CALLBACK_CONTAINMENT_MISMATCH)

private fun QueryCallbackBodyBindingDocument.admitDefaultSupply(
    parameter: RelationOccurrenceDocument
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (!parameter.contains(body.occurrence))
        return Refinement.Rejected(QueryCallbackDocumentFailure.CALLBACK_CONTAINMENT_MISMATCH)
    return when (val mapped = binding) {
        is QueryCallbackBindingDocument.Default -> mapped.admitDefaultMapping(parameter, body.occurrence)
        is QueryCallbackBindingDocument.Unavailable -> Refinement.Refined(Unit)
        is QueryCallbackBindingDocument.Bound,
        is QueryCallbackBindingDocument.Direct ->
            Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    }
}

private fun QueryCallbackBindingDocument.Default.admitDefaultMapping(
    suppliedParameter: RelationOccurrenceDocument,
    body: RelationOccurrenceDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (!parameter.validParameter() || !suppliedParameter.sameSite(parameter.parameter))
        return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    if (!parameter.parameter.contains(defaultValue) || !defaultValue.contains(body))
        return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun QueryCallbackBodyBindingDocument.admitDirectSupply(
    occurrence: RelationOccurrenceDocument,
    basis: ImpactSemanticBasisDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (!occurrence.contains(body.occurrence))
        return Refinement.Rejected(QueryCallbackDocumentFailure.CALLBACK_CONTAINMENT_MISMATCH)
    return when (val mapped = binding) {
        is QueryCallbackBindingDocument.Direct -> mapped.admitDirectMapping(occurrence, body.occurrence, basis)
        is QueryCallbackBindingDocument.Unavailable -> Refinement.Refined(Unit)
        is QueryCallbackBindingDocument.Bound,
        is QueryCallbackBindingDocument.Default ->
            Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    }
}

private fun QueryCallbackBindingDocument.Direct.admitDirectMapping(
    suppliedOccurrence: RelationOccurrenceDocument,
    body: RelationOccurrenceDocument,
    suppliedBasis: ImpactSemanticBasisDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val owner =
        when (val admitted = this.owner.admitOwner()) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    if (basis != suppliedBasis || !suppliedOccurrence.sameSite(occurrence))
        return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    if (!occurrence.contains(body) || !owner.contains(occurrence))
        return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun QueryCallbackBodyBindingDocument.admitUnavailableSupply(
    cause: QueryCallbackFlowCauseDocument
): Refinement<Unit, QueryCallbackDocumentFailure> =
    if (binding == QueryCallbackBindingDocument.Unavailable(cause)) Refinement.Refined(Unit)
    else Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)

private fun QueryCallbackBodyBindingDocument.admitInvocationSupply(
    supply: QueryCallbackBodySupplyDocument.Invocation,
    basis: ImpactSemanticBasisDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (!supply.occurrence.contains(body.occurrence))
        return Refinement.Rejected(QueryCallbackDocumentFailure.CALLBACK_CONTAINMENT_MISMATCH)
    return when (val mapped = binding) {
        is QueryCallbackBindingDocument.Unavailable -> Refinement.Refined(Unit)
        is QueryCallbackBindingDocument.Default,
        is QueryCallbackBindingDocument.Direct ->
            Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
        is QueryCallbackBindingDocument.Bound -> {
            if (!supply.occurrence.sameSite(mapped.invocationOccurrence))
                return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
            admitNestedMapping(mapped, basis)
        }
    }
}

private fun admitNestedMapping(
    bound: QueryCallbackBindingDocument.Bound,
    basis: ImpactSemanticBasisDocument,
): Refinement<Unit, QueryCallbackDocumentFailure> {
    if (DetachedVirtualFileUrl.parse(bound.invocation.callable.file.value) is Refinement.Refined)
        return Refinement.Rejected(QueryCallbackDocumentFailure.OWNER_BINDING_MISMATCH)
    val owner =
        when (val admitted = bound.invocationOwner.admitOwner()) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    if (!owner.contains(bound.invocationOccurrence))
        return Refinement.Rejected(QueryCallbackDocumentFailure.INVOCATION_OWNER_MISMATCH)
    if (!bound.validCallableMapping(basis) || !bound.callable.declaration.contains(bound.parameter))
        return Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    if (
        bound.invocation.range.start != bound.invocationOccurrence.range.startInclusive ||
            bound.invocation.range.end != bound.invocationOccurrence.range.endExclusive
    )
        return Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    val signature =
        bound.callable.compilerTarget.compilerEvidence.signature as? CompilerSignatureDocument.Function
            ?: return Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    if (bound.position.value !in signature.valueParameters.values.indices)
        return Refinement.Rejected(QueryCallbackDocumentFailure.PARAMETER_POSITION_MISMATCH)
    return Refinement.Refined(Unit)
}
